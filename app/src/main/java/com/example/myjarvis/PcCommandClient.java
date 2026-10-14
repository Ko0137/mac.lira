package com.example.myjarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class PcCommandClient {
    private static final String PREFERENCES = "LiraPrefs";
    private static final int PORT = 48522;

    private PcCommandClient() {
    }

    static Command parse(String text) {
        String[] prefixes = {
                "запусти на пк", "запусти на компьютере",
                "открой на пк", "открой на компьютере",
                "найди на пк", "найди на компьютере"
        };
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + " ")) {
                String action = prefix.startsWith("запусти") ? "launch_app"
                        : prefix.startsWith("открой") ? "open" : "search";
                return new Command(action, text.trim().substring(prefix.length()).trim());
            }
        }
        return null;
    }

    static String execute(Context context, Command command) throws IOException {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
        String host = preferences.getString("pc_host", "").trim();
        String token = preferences.getString("pc_token", "").trim();
        if (!isPrivateLanAddress(host) || !token.matches("\\d{8}")) {
            throw new IOException("Сначала синхронизируй телефон с ПК в настройках по Wi‑Fi.");
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://" + host + ":" + PORT + "/lira/command");
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(45000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-Lira-Token", token);
            byte[] payload = new JSONObject()
                    .put("action", command.action)
                    .put("query", command.query)
                    .toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            InputStream response = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (response == null) {
                throw new IOException("ПК вернул пустой ответ.");
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 8192) {
                        throw new IOException("Ответ ПК слишком большой.");
                    }
                }
            }
            if (status != HttpURLConnection.HTTP_OK) {
                JSONObject error = new JSONObject(body.toString());
                throw new IOException(error.optString("message",
                        "ПК ответил кодом " + status + "."));
            }
            JSONObject result = new JSONObject(body.toString());
            return result.optString("message", "Команда выполнена на ПК.");
        } catch (org.json.JSONException exception) {
            throw new IOException("Не удалось прочитать ответ ПК.", exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isPrivateLanAddress(String host) {
        if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            byte[] bytes = address.getAddress();
            if (bytes.length != 4 || address.isLoopbackAddress()
                    || address.isMulticastAddress()) {
                return false;
            }
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 10 || first == 192 && second == 168
                    || first == 172 && second >= 16 && second <= 31
                    || first == 169 && second == 254;
        } catch (IOException exception) {
            return false;
        }
    }

    static final class Command {
        final String action;
        final String query;

        private Command(String action, String query) {
            this.action = action;
            this.query = query;
        }
    }
}
