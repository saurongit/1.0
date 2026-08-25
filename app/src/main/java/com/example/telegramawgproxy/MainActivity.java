package com.example.telegramawgproxy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends Activity {
    private static final int REQUEST_CONFIG = 10;
    private static final int REQUEST_NOTIFICATIONS = 11;

    private TextView statusText;
    private TextView configText;
    private TextView modeText;
    private String pendingStartAction = ProxyService.ACTION_START;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CONFIG && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                ConfigStore.importConfig(this, data.getData());
                Toast.makeText(this, "Конфиг проверен и загружен", Toast.LENGTH_SHORT).show();
                refresh();
                requestProxyStart(ProxyService.ACTION_RESTART);
            } catch (Exception e) {
                Toast.makeText(this, "Ошибка конфига: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root);

        TextView title = text("AWG Messenger Proxy", 26, true);
        root.addView(title);

        TextView subtitle = text("Telegram: SOCKS5 127.0.0.1:1080.", 16, false);
        subtitle.setPadding(0, dp(8), 0, dp(16));
        root.addView(subtitle);

        statusText = text("", 15, false);
        statusText.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusText);

        configText = text("", 15, false);
        configText.setPadding(0, 0, 0, dp(16));
        root.addView(configText);

        modeText = text("", 15, false);
        modeText.setPadding(0, 0, 0, dp(8));
        root.addView(modeText);

        Button testProxyButton = button("Проверить выбранный прокси");
        testProxyButton.setOnClickListener(v -> testSelectedProxy());
        root.addView(testProxyButton);

        Button logButton = button("Показать лог запуска");
        logButton.setOnClickListener(v -> showLog());
        root.addView(logButton);

        Button helpButton = button("Инструкция");
        helpButton.setOnClickListener(v -> showHelp());
        root.addView(helpButton);

        Button importButton = button("Загрузить конфиг");
        importButton.setOnClickListener(v -> chooseConfig());
        root.addView(importButton);

        Button startButton = button("Запустить прокси");
        startButton.setOnClickListener(v -> startProxy());
        root.addView(startButton);

        Button stopButton = button("Остановить прокси");
        stopButton.setOnClickListener(v -> stopProxy());
        root.addView(stopButton);

        Button telegramProxyButton = button("Открыть прокси в Telegram");
        telegramProxyButton.setOnClickListener(v -> openTelegramProxyLink());
        root.addView(telegramProxyButton);

        Button telegramButton = button("Открыть Telegram");
        telegramButton.setOnClickListener(v -> openTelegram());
        root.addView(telegramButton);

        Button settingsButton = button("Настройки приложения");
        settingsButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + getPackageName()))));
        root.addView(settingsButton);

        Button backgroundButton = button("Разрешить работу в фоне");
        backgroundButton.setOnClickListener(v -> requestBackgroundAccess());
        root.addView(backgroundButton);

        setContentView(scroll);
    }

    private void refresh() {
        ConfigStore.saveProxyMode(this, ConfigStore.MODE_LOCAL_AWG);
        statusText.setText("Статус: " + (ConfigStore.hasConfig(this) ? "проверка" : "нужен конфиг"));
        configText.setText("Конфиг: " + ConfigStore.configName(this));
        modeText.setText("Режим: Local AWG, автозапуск " + (ConfigStore.proxyEnabled(this) ? "включен" : "выключен"));
        if (ConfigStore.hasConfig(this)) {
            new Thread(() -> {
                boolean running = isPortOpen(ConfigStore.SOCKS_HOST, ConfigStore.SOCKS_PORT, 300);
                runOnUiThread(() -> statusText.setText("Статус: " + (running ? "прокси запущен" : "готов к запуску")));
            }, "awg-status-check").start();
        }
    }

    private void chooseConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CONFIG);
    }

    private void startProxy() {
        requestProxyStart(ProxyService.ACTION_START);
    }

    private void requestProxyStart(String action) {
        ConfigStore.saveProxyMode(this, ConfigStore.MODE_LOCAL_AWG);
        if (!ConfigStore.hasConfig(this)) {
            Toast.makeText(this, "Сначала загрузите конфиг", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingStartAction = action;
        if (!ProxyService.canPostNotifications(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        startProxyService(action);
    }

    private void startProxyService(String action) {
        ProxyService.requestStart(this, action);
        pendingStartAction = ProxyService.ACTION_START;
        Toast.makeText(this, "Прокси запускается", Toast.LENGTH_SHORT).show();
        statusText.postDelayed(this::refresh, 1200);
    }

    private void stopProxy() {
        ProxyService.requestStart(this, ProxyService.ACTION_STOP);
        Toast.makeText(this, "Прокси остановлен", Toast.LENGTH_SHORT).show();
        statusText.postDelayed(this::refresh, 300);
    }

    private void openTelegramProxyLink() {
        ConfigStore.ProxyEndpoint endpoint = ConfigStore.activeEndpoint(this);
        if (!endpoint.isValid()) {
            Toast.makeText(this, "Прокси не задан", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri.Builder uriBuilder = new Uri.Builder()
                .scheme("tg")
                .authority("socks")
                .appendQueryParameter("server", endpoint.host)
                .appendQueryParameter("port", String.valueOf(endpoint.port));
        if (!endpoint.user.isEmpty()) {
            uriBuilder.appendQueryParameter("user", endpoint.user);
            uriBuilder.appendQueryParameter("pass", endpoint.password);
        }
        Uri uri = uriBuilder.build();
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage("org.telegram.messenger");
        try {
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        }
    }

    private void openTelegram() {
        openPackage("org.telegram.messenger", "Telegram не найден");
    }

    private void openPackage(String packageName, String notFoundMessage) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            Toast.makeText(this, notFoundMessage, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(intent);
    }

    private void copyProxyAddress() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            ConfigStore.ProxyEndpoint endpoint = ConfigStore.activeEndpoint(this);
            if (!endpoint.isValid()) {
                Toast.makeText(this, "Прокси не задан", Toast.LENGTH_SHORT).show();
                return;
            }
            String proxy = endpoint.hostPort();
            manager.setPrimaryClip(ClipData.newPlainText("AWG SOCKS5 proxy", proxy));
            Toast.makeText(this, "Скопировано: " + proxy, Toast.LENGTH_SHORT).show();
        }
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Как это работает")
                .setMessage(
                        "Local AWG\n"
                                + "1. Нажмите «Загрузить конфиг» и выберите полученный исходный AWG/WG-файл. Приложение само подготовит и проверит его, затем запустит прокси.\n\n"
                                + "2. Разрешите уведомления и нажмите «Разрешить работу в фоне». Это требуется один раз.\n\n"
                                + "3. Дождитесь статуса «прокси запущен» и нажмите «Проверить выбранный прокси». Успешный результат: «Туннель работает».\n\n"
                                + "4. Нажмите «Открыть прокси в Telegram» и один раз включите предложенный SOCKS5-прокси.\n\n"
                                + "После настройки переключение Wi-Fi/мобильной сети и переподключение AWG происходят автоматически. Не нажимайте «Остановить прокси», не останавливайте приложение принудительно и не выключайте прокси в Telegram.\n\n"
                                + "Для каждого телефона нужен отдельный исходный конфиг. Утилита не занимает Android VPN-слот: остальные приложения продолжают использовать обычное подключение."
                )
                .setPositiveButton("Понял", null)
                .show();
    }

    private void showLog() {
        String value;
        try {
            value = readLogTail();
        } catch (IOException e) {
            value = "Лог пока пуст или не читается: " + e.getMessage();
        }
        new AlertDialog.Builder(this)
                .setTitle("Лог запуска")
                .setMessage(value.isEmpty() ? "Лог пока пуст" : value)
                .setPositiveButton("Закрыть", null)
                .show();
    }

    private String readLogTail() throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(ConfigStore.logFile(this)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
                if (builder.length() > 5000) {
                    builder.delete(0, builder.length() - 5000);
                }
            }
        }
        return builder.toString();
    }

    private void testSelectedProxy() {
        ConfigStore.ProxyEndpoint endpoint = ConfigStore.activeEndpoint(this);
        if (!endpoint.isValid()) {
            Toast.makeText(this, "Прокси не задан", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Проверяю " + endpoint.hostPort(), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            long elapsedMs = testSocksTunnel(endpoint.host, endpoint.port);
            runOnUiThread(() -> Toast.makeText(
                    this,
                    elapsedMs >= 0
                            ? "Туннель работает: " + elapsedMs + " мс"
                            : "Туннель не отвечает: " + endpoint.hostPort(),
                    Toast.LENGTH_LONG
            ).show());
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startProxyService(pendingStartAction);
            } else {
                pendingStartAction = ProxyService.ACTION_START;
                Toast.makeText(this, "Без уведомления Android может скрыть состояние прокси", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestBackgroundAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Дополнительное разрешение не требуется", Toast.LENGTH_SHORT).show();
            return;
        }
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager != null && manager.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Работа в фоне уже разрешена", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static long testSocksTunnel(String host, int port) {
        long startedAt = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(15000);
            OutputStream output = socket.getOutputStream();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            output.write(new byte[]{5, 1, 0});
            output.flush();
            byte[] greeting = new byte[2];
            input.readFully(greeting);
            if (greeting[0] != 5 || greeting[1] != 0) {
                return -1;
            }
            output.write(new byte[]{5, 1, 0, 1, 1, 1, 1, 1, 0, 80});
            output.flush();
            byte[] response = new byte[2];
            input.readFully(response);
            if (response[0] != 5 || response[1] != 0) {
                return -1;
            }
            return System.currentTimeMillis() - startedAt;
        } catch (IOException e) {
            return -1;
        }
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        return button;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(size);
        if (bold) {
            textView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return textView;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
