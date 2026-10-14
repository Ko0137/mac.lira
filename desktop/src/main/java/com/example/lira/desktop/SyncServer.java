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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

final class SyncServer implements AutoCloseable {
    static final int PORT = 48522;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final int MAX_COMMAND_BYTES = 4 * 1024;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long AUTH_LOCKOUT_NANOS = 60_000_000_000L;
    private static final long AUTH_FAILURE_TTL_NANOS = 300_000_000_000L;

    private final DesktopStore store;
    private final String token;
    private final RemoteCommandHandler commandHandler;
    private final Map<String, AuthFailures> authFailures = new HashMap<>();
    private HttpServer server;

    SyncServer(DesktopStore store, String token, RemoteCommandHandler commandHandler) {
        this.store = store;
        this.token = token;
        this.commandHandler = commandHandler;
    }

    void start() throws IOException {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 8);
        server.createContext("/lira/sync", this::handleSync);
        server.createContext("/lira/command", this::handleCommand);
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "lira-wifi-sync");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    private void handleSync(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }
            int authStatus = authenticate(exchange);
            if (authStatus != 200) {
                respond(exchange, authStatus, "{\"error\":\"Invalid or blocked pairing token\"}");
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

    private void handleCommand(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }
            int authStatus = authenticate(exchange);
            if (authStatus != 200) {
                respond(exchange, authStatus, "{\"error\":\"Invalid or blocked pairing token\"}");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_COMMAND_BYTES + 1);
            if (body.length > MAX_COMMAND_BYTES) {
                respond(exchange, 413, "{\"error\":\"Command is too large\"}");
                return;
            }
            try {
                JsonObject request = JsonParser.parseString(
                        new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!isString(request, "action") || !isString(request, "query")) {
                    respond(exchange, 400, "{\"error\":\"Invalid command\"}");
                    return;
                }
                RemoteCommandHandler.Result result = commandHandler.execute(
                        request.get("action").getAsString(),
                        request.get("query").getAsString());
                JsonObject response = new JsonObject();
                response.addProperty("success", result.success());
                response.addProperty("message", result.message());
                respond(exchange, result.success() ? 200 : 422, response.toString());
            } catch (RuntimeException exception) {
                respond(exchange, 400, "{\"error\":\"Invalid command payload\"}");
            }
        }
    }

    private boolean isString(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isString();
    }

    private synchronized int authenticate(HttpExchange exchange) {
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        long now = System.nanoTime();
        AuthFailures failures = authFailures.get(address);
        if (failures != null && failures.blockedUntilNanos > now) {
            return 429;
        }

        String header = exchange.getRequestHeaders().getFirst("X-Lira-Token");
        byte[] provided = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), provided)) {
            authFailures.remove(address);
            return 200;
        }

        if (failures == null || failures.blockedUntilNanos != 0) {
            authFailures.entrySet().removeIf(entry ->
                    now - entry.getValue().lastAttemptNanos > AUTH_FAILURE_TTL_NANOS
                            && entry.getValue().blockedUntilNanos <= now);
            if (failures == null && authFailures.size() >= 256) {
                return 429;
            }
            failures = new AuthFailures();
            authFailures.put(address, failures);
        }
        failures.failures++;
        failures.lastAttemptNanos = now;
        if (failures.failures >= MAX_FAILED_ATTEMPTS) {
            failures.blockedUntilNanos = now + AUTH_LOCKOUT_NANOS;
            failures.failures = 0;
            return 429;
        }
        return 401;
    }

    private static final class AuthFailures {
        private int failures;
        private long blockedUntilNanos;
        private long lastAttemptNanos;
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
