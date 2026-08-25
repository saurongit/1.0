package com.example.telegramawgproxy;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ProxyService extends Service {
    static final String ACTION_START = "com.example.telegramawgproxy.START";
    static final String ACTION_RESTART = "com.example.telegramawgproxy.RESTART";
    static final String ACTION_STOP = "com.example.telegramawgproxy.STOP";
    static final String TAG = "TelegramAwgProxy";

    private static final String CHANNEL_ID = "telegram_awg_proxy";
    private static final int NOTIFICATION_ID = 20;
    private static final long NETWORK_RESTART_DELAY_MS = 750;
    private static final long NETWORK_SETTLE_WINDOW_MS = 10000;
    private static final long STABLE_PROCESS_MS = 60000;
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;

    private final Object processLock = new Object();
    private final ScheduledExecutorService controlExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final RestartPolicy restartPolicy = new RestartPolicy();
    private final NetworkChangeDetector networkChangeDetector = new NetworkChangeDetector();
    private final NetworkRestartGate networkRestartGate = new NetworkRestartGate(NETWORK_SETTLE_WINDOW_MS);

    private volatile Process process;
    private volatile boolean desiredRunning;
    private volatile boolean destroyed;
    private long processGeneration;
    private ScheduledFuture<?> pendingRestart;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver legacyConnectivityReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "service onCreate");
        createNotificationChannel();
        startForegroundCompat(notification("Инициализация"));
        registerNetworkMonitoring();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        Log.i(TAG, "service onStartCommand action=" + action + " startId=" + startId);
        if (ACTION_STOP.equals(action)) {
            ConfigStore.saveProxyEnabled(this, false);
            desiredRunning = false;
            submitControl(() -> {
                cancelPendingRestart();
                stopProxy();
                removeForeground();
                stopSelf();
            });
            return START_NOT_STICKY;
        }

        ConfigStore.saveProxyEnabled(this, true);
        desiredRunning = true;
        if (ACTION_RESTART.equals(action)) {
            submitControl(() -> restartProxy("configuration reloaded"));
        } else {
            submitControl(this::ensureProxyRunning);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        unregisterNetworkMonitoring();
        cancelPendingRestart();
        stopProxy();
        controlExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void requestStart(Context context, String action) {
        Intent intent = new Intent(context, ProxyService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private void ensureProxyRunning() {
        if (!desiredRunning || destroyed) {
            return;
        }
        Process current = process;
        if (current != null && isAlive(current)) {
            updateNotification("Работает: SOCKS 1080");
            return;
        }
        startProxy();
    }

    private void startProxy() {
        if (!desiredRunning || destroyed) {
            return;
        }
        if (!ConfigStore.hasConfig(this)) {
            updateNotification("Загрузите AWG/WG конфиг");
            Log.w(TAG, "No config imported");
            return;
        }

        try {
            File logFile = ConfigStore.logFile(this);
            rotateLogIfNeeded(logFile);
            File binaryFile = ConfigStore.packagedBinaryFile(this);
            binaryFile.setExecutable(true, false);
            Log.i(TAG, "starting wireproxy binary=" + binaryFile.getAbsolutePath());
            appendLog(logFile, "starting " + binaryFile.getAbsolutePath() + "\n");
            ProcessBuilder builder = new ProcessBuilder(
                    binaryFile.getAbsolutePath(),
                    "-c",
                    ConfigStore.configFile(this).getAbsolutePath()
            );
            builder.directory(getFilesDir());
            builder.redirectErrorStream(true);
            Process started = builder.start();
            if (!desiredRunning || destroyed) {
                started.destroy();
                return;
            }
            long generation;
            synchronized (processLock) {
                process = started;
                generation = ++processGeneration;
            }
            updateNotification("Запускается");
            Log.i(TAG, "wireproxy started generation=" + generation);
            ioExecutor.execute(() -> copyProcessOutput(started, logFile));
            ioExecutor.execute(() -> waitForProcess(started, generation));
            controlExecutor.schedule(() -> markProcessStable(started, generation), STABLE_PROCESS_MS, TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start wireproxy", e);
            scheduleCrashRestart("ошибка запуска");
        }
    }

    private void waitForProcess(Process runningProcess, long generation) {
        int code;
        try {
            code = runningProcess.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        int exitCode = code;
        submitControl(() -> handleProcessExit(runningProcess, generation, exitCode));
    }

    private void handleProcessExit(Process exitedProcess, long generation, int code) {
        synchronized (processLock) {
            if (process != exitedProcess || processGeneration != generation) {
                return;
            }
            process = null;
        }
        Log.w(TAG, "wireproxy exited generation=" + generation + " code=" + code);
        if (desiredRunning && !destroyed) {
            scheduleCrashRestart("wireproxy завершился, код " + code);
        }
    }

    private void scheduleCrashRestart(String reason) {
        if (!desiredRunning || destroyed) {
            return;
        }
        cancelPendingRestart();
        long delay = restartPolicy.nextDelayMs();
        updateNotification("Перезапуск через " + Math.max(1, delay / 1000) + " с");
        Log.w(TAG, reason + "; restart in " + delay + " ms");
        pendingRestart = controlExecutor.schedule(this::ensureProxyRunning, delay, TimeUnit.MILLISECONDS);
    }

    private void scheduleNetworkRestart(String reason) {
        if (!desiredRunning || destroyed) {
            return;
        }
        submitControl(() -> {
            cancelPendingRestart();
            pendingRestart = controlExecutor.schedule(
                    () -> restartProxy(reason),
                    NETWORK_RESTART_DELAY_MS,
                    TimeUnit.MILLISECONDS
            );
        });
    }

    private void restartProxy(String reason) {
        if (!desiredRunning || destroyed) {
            return;
        }
        cancelPendingRestart();
        restartPolicy.reset();
        Log.i(TAG, "restarting wireproxy: " + reason);
        updateNotification("Переподключение");
        terminateWireproxy();
        startProxy();
    }

    private void markProcessStable(Process stableProcess, long generation) {
        synchronized (processLock) {
            if (process == stableProcess && processGeneration == generation && isAlive(stableProcess)) {
                restartPolicy.reset();
                updateNotification("Работает: SOCKS 1080");
                Log.i(TAG, "wireproxy stable generation=" + generation);
            }
        }
    }

    private void stopProxy() {
        terminateWireproxy();
        updateNotification("Остановлен");
    }

    private void terminateWireproxy() {
        Process running;
        synchronized (processLock) {
            running = process;
            process = null;
            processGeneration++;
        }
        if (running == null) {
            return;
        }
        running.destroy();
        long deadline = System.currentTimeMillis() + 2000;
        while (isAlive(running) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (isAlive(running)) {
            Log.w(TAG, "wireproxy did not stop within 2 seconds");
        }
    }

    private void cancelPendingRestart() {
        if (pendingRestart != null) {
            pendingRestart.cancel(false);
            pendingRestart = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void registerNetworkMonitoring() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            Log.w(TAG, "ConnectivityManager is unavailable");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (networkChangeDetector.onAvailable(network.toString())) {
                        networkRestartGate.onDefaultNetworkChanged(SystemClock.elapsedRealtime());
                        scheduleNetworkRestart("default network changed");
                    }
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    if (networkChangeDetector.onLinkProperties(network.toString(), linkFingerprint(linkProperties))) {
                        if (networkRestartGate.shouldRestartForLinkChange(SystemClock.elapsedRealtime())) {
                            scheduleNetworkRestart("network address changed");
                        } else {
                            Log.i(TAG, "coalesced link update during network settle window");
                        }
                    }
                }

                @Override
                public void onLost(Network network) {
                    networkChangeDetector.onLost(network.toString());
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            return;
        }

        legacyConnectivityReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleLegacyNetworkChange();
            }
        };
        registerReceiver(legacyConnectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        handleLegacyNetworkChange();
    }

    private void handleLegacyNetworkChange() {
        if (connectivityManager == null) {
            return;
        }
        Network active = connectivityManager.getActiveNetwork();
        if (active == null) {
            return;
        }
        boolean networkChanged = networkChangeDetector.onAvailable(active.toString());
        if (networkChanged) {
            networkRestartGate.onDefaultNetworkChanged(SystemClock.elapsedRealtime());
        }
        boolean linkChanged = false;
        LinkProperties properties = connectivityManager.getLinkProperties(active);
        if (properties != null) {
            linkChanged = networkChangeDetector.onLinkProperties(active.toString(), linkFingerprint(properties));
        }
        if (networkChanged) {
            scheduleNetworkRestart("default network changed");
        } else if (linkChanged && networkRestartGate.shouldRestartForLinkChange(SystemClock.elapsedRealtime())) {
            scheduleNetworkRestart("network address changed");
        }
    }

    private void unregisterNetworkMonitoring() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to unregister network callback", e);
            }
            networkCallback = null;
        }
        if (legacyConnectivityReceiver != null) {
            try {
                unregisterReceiver(legacyConnectivityReceiver);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to unregister connectivity receiver", e);
            }
            legacyConnectivityReceiver = null;
        }
    }

    private static String linkFingerprint(LinkProperties properties) {
        return String.valueOf(properties.getInterfaceName())
                + '|' + properties.getLinkAddresses()
                + '|' + properties.getDnsServers()
                + '|' + properties.getMtu();
    }

    @SuppressWarnings("deprecation")
    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        int immutableFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag
        );
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                2,
                new Intent(this, ProxyService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag
        );
        return builder
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause,
                        "Остановить",
                        stopIntent
                ).build())
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @SuppressWarnings("deprecation")
    private void removeForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && !destroyed) {
            manager.notify(NOTIFICATION_ID, notification(text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    static boolean canPostNotifications(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void copyProcessOutput(Process runningProcess, File logFile) {
        byte[] buffer = new byte[4096];
        try (InputStream input = runningProcess.getInputStream();
             OutputStream output = new FileOutputStream(logFile, true)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException e) {
            boolean current;
            synchronized (processLock) {
                current = process == runningProcess;
            }
            if (!destroyed && current) {
                Log.w(TAG, "Failed to copy wireproxy output", e);
            } else {
                Log.i(TAG, "wireproxy output closed");
            }
        }
    }

    private void rotateLogIfNeeded(File logFile) {
        if (!logFile.isFile() || logFile.length() <= MAX_LOG_BYTES) {
            return;
        }
        File previous = new File(logFile.getParentFile(), logFile.getName() + ".1");
        if (previous.exists()) {
            previous.delete();
        }
        if (!logFile.renameTo(previous)) {
            Log.w(TAG, "Failed to rotate wireproxy log");
        }
    }

    private void appendLog(File logFile, String value) {
        try (OutputStream output = new FileOutputStream(logFile, true)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Failed to append wireproxy log", e);
        }
    }

    private void submitControl(Runnable task) {
        if (destroyed) {
            return;
        }
        try {
            controlExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static boolean isAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException ignored) {
            return true;
        }
    }
}
