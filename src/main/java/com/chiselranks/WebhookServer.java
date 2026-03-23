package com.chiselranks;

import com.chiselranks.RankManager.GrantAction;
import com.chiselranks.rank.Rank;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebhookServer {
    private static final Pattern JSON_FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"([^\"]*)\"|[^,}\\s]+)");

    private final ChiselRanksPlugin plugin;
    private final RankManager rankManager;

    private HttpServer server;

    public WebhookServer(ChiselRanksPlugin plugin, RankManager rankManager) {
        this.plugin = plugin;
        this.rankManager = rankManager;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("webhook.enabled", true)) {
            return;
        }

        String host = plugin.getConfig().getString("webhook.host", "0.0.0.0");
        int port = plugin.getConfig().getInt("webhook.port", 8765);
        String path = normalizePath(plugin.getConfig().getString("webhook.path", "/ranks/webhook"));

        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext(path, new RankWebhookHandler());
            server.setExecutor(Executors.newCachedThreadPool(new WebhookThreadFactory()));
            server.start();
            plugin.getLogger().info("Webhook listener started on " + host + ":" + port + path);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to start webhook listener: " + exception.getMessage());
            server = null;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/ranks/webhook";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private final class RankWebhookHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, false, "Only POST is allowed");
                return;
            }

            if (!isAuthorized(exchange.getRequestHeaders())) {
                sendJson(exchange, 401, false, "Invalid webhook secret");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            WebhookPayload payload = parsePayload(body, exchange.getRequestHeaders());
            if (payload == null) {
                sendJson(exchange, 400, false, "Invalid payload. Expected uuid or player_name plus rank/action.");
                return;
            }

            CompletableFuture<WebhookResult> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> future.complete(processPayload(payload)));

            try {
                WebhookResult result = future.get(10, TimeUnit.SECONDS);
                sendJson(exchange, result.ok ? 200 : 400, result.ok, result.message);
            } catch (Exception exception) {
                sendJson(exchange, 500, false, "Webhook processing failed: " + exception.getMessage());
            }
        }

        private boolean isAuthorized(Headers headers) {
            String headerName = plugin.getConfig().getString("webhook.secret-header", "X-ChiselRanks-Secret");
            String expected = plugin.getConfig().getString("webhook.secret", "change-me");
            String provided = headerName == null ? null : headers.getFirst(headerName);
            return expected != null && !expected.isBlank() && expected.equals(provided);
        }

        private WebhookResult processPayload(WebhookPayload payload) {
            UUID uuid = payload.uuid;
            if (uuid == null && payload.playerName != null && !payload.playerName.isBlank()) {
                Player online = Bukkit.getPlayerExact(payload.playerName);
                if (online != null) {
                    uuid = online.getUniqueId();
                } else {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(payload.playerName);
                    if (offline != null && offline.getUniqueId() != null) {
                        uuid = offline.getUniqueId();
                    }
                }
            }

            if (uuid == null) {
                return new WebhookResult(false, "Could not resolve player uuid");
            }

            Rank rank = payload.rank;
            if (payload.action != GrantAction.CLEAR && rank == null) {
                return new WebhookResult(false, "Missing or invalid rank");
            }

            Rank applied = rankManager.grantRank(uuid, rank == null ? Rank.NONE : rank, payload.action);
            if (payload.action != GrantAction.CLEAR && plugin.getDiscordWebhookService() != null) {
                String displayName = payload.playerName != null && !payload.playerName.isBlank() ? payload.playerName : uuid.toString();
                Bukkit.broadcast(net.kyori.adventure.text.Component.text("[ChiselRanks] " + displayName + " bought the " + applied.getDisplayName() + " rank."));
                plugin.getDiscordWebhookService().sendStorePurchase(displayName, applied.getDisplayName() + " Rank",
                        "Rank purchase processed by rank webhook.");
            }
            return new WebhookResult(true, "Applied rank " + applied.getKey() + " to " + uuid);
        }

        private WebhookPayload parsePayload(String body, Headers headers) {
            Map<String, String> values = parseBody(body, headers.getFirst("Content-Type"));
            if (values.isEmpty()) {
                return null;
            }

            UUID uuid = null;
            String uuidText = firstPresent(values, "uuid", "player_uuid");
            if (uuidText != null && !uuidText.isBlank()) {
                try {
                    uuid = UUID.fromString(uuidText);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }

            String playerName = firstPresent(values, "player_name", "username", "player", "name");
            String actionText = firstPresent(values, "action");
            if (actionText == null || actionText.isBlank()) {
                actionText = plugin.getConfig().getString("webhook.default-action", "grant");
            }

            GrantAction action;
            try {
                action = GrantAction.valueOf(actionText.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return null;
            }

            Rank rank = Rank.fromKey(firstPresent(values, "rank", "group", "role")).orElse(null);
            if (uuid == null && (playerName == null || playerName.isBlank())) {
                return null;
            }

            return new WebhookPayload(uuid, playerName, rank, action);
        }

        private Map<String, String> parseBody(String body, String contentType) {
            Map<String, String> values = new HashMap<>();
            if (body == null || body.isBlank()) {
                return values;
            }

            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("x-www-form-urlencoded")) {
                String[] pairs = body.split("&");
                for (String pair : pairs) {
                    String[] split = pair.split("=", 2);
                    String key = URLDecoder.decode(split[0], StandardCharsets.UTF_8);
                    String value = split.length > 1 ? URLDecoder.decode(split[1], StandardCharsets.UTF_8) : "";
                    values.put(key.toLowerCase(Locale.ROOT), value);
                }
                return values;
            }

            Matcher matcher = JSON_FIELD.matcher(body);
            while (matcher.find()) {
                String raw = matcher.group(3) != null ? matcher.group(3) : matcher.group(2);
                values.put(matcher.group(1).toLowerCase(Locale.ROOT), raw);
            }
            return values;
        }

        private String firstPresent(Map<String, String> values, String... keys) {
            for (String key : keys) {
                String value = values.get(key.toLowerCase(Locale.ROOT));
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
    }

    private record WebhookPayload(UUID uuid, String playerName, Rank rank, GrantAction action) {
    }

    private record WebhookResult(boolean ok, String message) {
    }

    private void sendJson(HttpExchange exchange, int status, boolean ok, String message) throws IOException {
        byte[] response = ("{\"ok\":" + ok + ",\"message\":\"" + escapeJson(message) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String escapeJson(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class WebhookThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ChiselRanks-Webhook");
            thread.setDaemon(true);
            return thread;
        }
    }
}