package com.example.lira.desktop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;

final class SyncServer implements AutoCloseable {
    static final int PORT = 48522;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private final DesktopStore store;
    private final String token;
    private HttpServer server;

    SyncServer(DesktopStore store, String token) {
        this.store = store;
        this.token = token;
    }

    void start() throws IOException {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 8);
        server.createContext("/lira/sync", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "lira-wifi-sync");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }
            byte[] provided = exchange.getRequestHeaders().getFirst("X-Lira-Token") == null
                    ? new byte[0] : exchange.getRequestHeaders().getFirst("X-Lira-Token")
                    .getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), provided)) {
                respond(exchange, 401, "{\"error\":\"Invalid pairing token\"}");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_PAYLOAD_BYTES + 1);
            if (body.length > MAX_PAYLOAD_BYTES) {
                respond(exchange, 413, "{\"error\":\"Sync data is too large\"}");
                return;
            }
            JsonObject incoming = JsonParser.parseString(
                    new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            store.mergeSyncSnapshot(incoming);
            respond(exchange, 200, store.syncSnapshot().toString());
        } catch (SQLException | RuntimeException exception) {
            byte[] body = "{\"error\":\"Sync failed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }
    }

    private void respond(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static List<String> localAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> values = network.getInetAddresses();
                while (values.hasMoreElements()) {
                    InetAddress address = values.nextElement();
                    if (!address.isLoopbackAddress() && address.getAddress().length == 4) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (IOException exception) {
            return List.of();
        }
        return addresses;
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
