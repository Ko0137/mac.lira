package com.example.lira.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncServerCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void commandEndpointRequiresPairingCodeAndAcceptsOnlyKnownActions() throws Exception {
        try (DesktopStore store = new DesktopStore(temporaryDirectory);
             SyncServer server = new SyncServer(store, "12345678",
                     new RemoteCommandHandler(new ApplicationLauncher()))) {
            server.start();

            assertEquals(401, post("invalid", "{\"action\":\"search\",\"query\":\"weather\"}")
                    .status());
            Response unsupported = post("12345678",
                    "{\"action\":\"run_process\",\"query\":\"anything\"}");
            assertEquals(422, unsupported.status());
            assertTrue(unsupported.body().contains("Неизвестный тип команды"));
        }
    }

    private Response post(String token, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + SyncServer.PORT + "/lira/command").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-Lira-Token", token);
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            var stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? ""
                    : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new Response(status, body);
        } finally {
            connection.disconnect();
        }
    }

    private record Response(int status, String body) {
    }
}
