package com.example.telegramawgproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class ConfigStore {
    static final String SOCKS_HOST = "127.0.0.1";
    static final int SOCKS_PORT = 1080;
    static final String MODE_DIRECT = "direct";
    static final String MODE_LOCAL_AWG = "local_awg";
    static final String MODE_SERVER_SOCKS = "server_socks";

    private static final String PREFS = "telegram_awg_proxy";
    private static final String KEY_CONFIG_NAME = "config_name";
    private static final String KEY_PROXY_MODE = "proxy_mode";
    private static final String KEY_SERVER_HOST = "server_host";
    private static final String KEY_SERVER_PORT = "server_port";
    private static final String KEY_SERVER_USER = "server_user";
    private static final String KEY_SERVER_PASS = "server_pass";
    private static final String KEY_PROXY_ENABLED = "proxy_enabled";
    private static final int MAX_CONFIG_CHARS = 1024 * 1024;

    private ConfigStore() {
    }

    static File configFile(Context context) {
        return new File(context.getFilesDir(), "wireproxy.conf");
    }

    static File binaryFile(Context context) {
        return new File(context.getFilesDir(), "wireproxy");
    }

    static File packagedBinaryFile(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libwireproxy.so");
    }

    static File logFile(Context context) {
        return new File(context.getFilesDir(), "wireproxy.log");
    }

    static boolean hasConfig(Context context) {
        File file = configFile(context);
        return file.isFile() && file.length() > 0;
    }

    static String configName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String name = prefs.getString(KEY_CONFIG_NAME, "");
        if (name.isEmpty() && hasConfig(context)) {
            return configFile(context).getName();
        }
        return name.isEmpty() ? "конфиг не загружен" : name;
    }

    static String proxyMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROXY_MODE, MODE_LOCAL_AWG);
    }

    static void saveProxyMode(Context context, String mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROXY_MODE, mode)
                .apply();
    }

    static boolean proxyEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PROXY_ENABLED, hasConfig(context));
    }

    static void saveProxyEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PROXY_ENABLED, enabled)
                .apply();
    }

    static ProxyEndpoint activeEndpoint(Context context) {
        return new ProxyEndpoint(SOCKS_HOST, SOCKS_PORT, "", "");
    }

    static String serverProxyDisplay(Context context) {
        ProxyEndpoint endpoint = activeEndpoint(context);
        if (!MODE_SERVER_SOCKS.equals(proxyMode(context)) || !endpoint.isValid()) {
            return "не задан";
        }
        return endpoint.host + ":" + endpoint.port;
    }

    static void saveServerProxy(Context context, String value) {
        ProxyEndpoint endpoint = ProxyEndpoint.parse(value);
        if (!endpoint.isValid()) {
            throw new IllegalArgumentException("Нужен формат host:port или socks5://user:pass@host:port");
        }
        saveServerProxy(context, endpoint.host, endpoint.port, endpoint.user, endpoint.password);
    }

    static void saveServerProxy(Context context, String host, int port, String user, String password) {
        ProxyEndpoint endpoint = new ProxyEndpoint(host, port, user, password);
        if (!endpoint.isValid()) {
            throw new IllegalArgumentException("Заполните host и port 1-65535");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER_HOST, endpoint.host)
                .putInt(KEY_SERVER_PORT, endpoint.port)
                .putString(KEY_SERVER_USER, endpoint.user)
                .putString(KEY_SERVER_PASS, endpoint.password)
                .apply();
    }

    static void importConfig(Context context, Uri uri) throws IOException {
        String text;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Не удалось открыть файл");
            }
            text = readUtf8(input);
        }
        String normalized = withLocalSocks(text);
        File target = configFile(context);
        File pending = new File(context.getFilesDir(), target.getName() + ".import");
        File backup = new File(context.getFilesDir(), target.getName() + ".backup");
        deleteQuietly(pending);
        try (OutputStream output = new FileOutputStream(pending)) {
            output.write(normalized.getBytes(StandardCharsets.UTF_8));
        }
        try {
            validateConfig(context, pending);
            replaceConfig(target, pending, backup);
        } catch (IOException e) {
            deleteQuietly(pending);
            throw e;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIG_NAME, displayName(context, uri))
                .apply();
    }

    private static String readUtf8(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[8192];
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
                if (builder.length() > MAX_CONFIG_CHARS) {
                    throw new IOException("Конфиг больше 1 МБ");
                }
            }
        }
        return builder.toString();
    }

    static String withLocalSocks(String text) {
        StringBuilder builder = new StringBuilder();
        boolean skippingSocks = false;
        boolean firstOutputLine = true;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                skippingSocks = "[Socks5]".equalsIgnoreCase(trimmed);
                if (skippingSocks) {
                    continue;
                }
            }
            if (!skippingSocks) {
                if (!firstOutputLine) {
                    builder.append('\n');
                }
                builder.append(line);
                firstOutputLine = false;
            }
        }
        builder.append("\n\n")
                .append("[Socks5]\n")
                .append("BindAddress = ")
                .append(SOCKS_HOST)
                .append(':')
                .append(SOCKS_PORT)
                .append('\n');
        return builder.toString();
    }

    private static void validateConfig(Context context, File file) throws IOException {
        Process process = new ProcessBuilder(
                packagedBinaryFile(context).getAbsolutePath(),
                "-n",
                "-c",
                file.getAbsolutePath()
        ).redirectErrorStream(true).start();

        long deadline = System.currentTimeMillis() + 5000;
        while (isAlive(process) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroy();
                throw new IOException("Проверка конфига прервана", e);
            }
        }
        if (isAlive(process)) {
            process.destroy();
            throw new IOException("Проверка конфига превысила 5 секунд");
        }
        if (process.exitValue() != 0) {
            throw new IOException("wireproxy не принял конфиг");
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

    private static void replaceConfig(File target, File pending, File backup) throws IOException {
        deleteQuietly(backup);
        boolean hadTarget = target.isFile();
        if (hadTarget && !target.renameTo(backup)) {
            throw new IOException("Не удалось сохранить предыдущий конфиг");
        }
        if (!pending.renameTo(target)) {
            if (hadTarget) {
                backup.renameTo(target);
            }
            throw new IOException("Не удалось установить новый конфиг");
        }
        deleteQuietly(backup);
    }

    private static void deleteQuietly(File file) {
        if (file.exists()) {
            file.delete();
        }
    }

    private static String displayName(Context context, Uri uri) {
        String result = null;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    result = cursor.getString(index);
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (result == null || result.trim().isEmpty()) {
            result = uri.getLastPathSegment();
        }
        return result == null ? "wireproxy.conf" : result;
    }

    static final class ProxyEndpoint {
        final String host;
        final int port;
        final String user;
        final String password;

        ProxyEndpoint(String host, int port, String user, String password) {
            this.host = host == null ? "" : host.trim();
            this.port = port;
            this.user = user == null ? "" : user.trim();
            this.password = password == null ? "" : password;
        }

        boolean isValid() {
            return !host.isEmpty() && port > 0 && port <= 65535;
        }

        String hostPort() {
            return host + ":" + port;
        }

        static ProxyEndpoint parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.startsWith("socks5://")) {
                value = value.substring("socks5://".length());
            }

            String user = "";
            String pass = "";
            int at = value.lastIndexOf('@');
            if (at >= 0) {
                String auth = value.substring(0, at);
                value = value.substring(at + 1);
                int colon = auth.indexOf(':');
                if (colon >= 0) {
                    user = auth.substring(0, colon);
                    pass = auth.substring(colon + 1);
                } else {
                    user = auth;
                }
            }

            int colon = value.lastIndexOf(':');
            if (colon <= 0 || colon == value.length() - 1) {
                return new ProxyEndpoint("", 0, "", "");
            }
            int port;
            try {
                port = Integer.parseInt(value.substring(colon + 1));
            } catch (NumberFormatException e) {
                port = 0;
            }
            return new ProxyEndpoint(value.substring(0, colon), port, user, pass);
        }
    }
}
