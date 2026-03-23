package com.beyondminer.leaderboards.web;

import com.beyondminer.bounty.database.DatabaseManager;
import com.beyondminer.leaderboards.BountyLeaderboards;
import com.beyondminer.leaderboards.managers.LeaderboardManager;
import com.beyondminer.leaderboards.models.LeaderboardEntry;
import com.beyondminer.skin.SkinHeadService;
import com.chisellives.ChiselLives;
import com.chisellives.LivesManager;
import com.chiselranks.RankManager;
import com.chiselranks.rank.Rank;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebSyncServer {

    private static final Pattern JSON_FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"([^\"]*)\"|[^,}\\s]+)");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final BountyLeaderboards plugin;
    private final DatabaseManager bountyDatabaseManager;
    private final LeaderboardManager leaderboardManager;
    private final ChiselLives chiselLives;

    private HttpServer server;
    private String basePath;
    private String corsAllowOrigin;
    private String purchaseSecretHeader;
    private String purchaseSecret;
    private boolean purchaseEndpointEnabled;

    public WebSyncServer(
            BountyLeaderboards plugin,
            DatabaseManager bountyDatabaseManager,
            LeaderboardManager leaderboardManager,
            ChiselLives chiselLives
    ) {
        this.plugin = plugin;
        this.bountyDatabaseManager = bountyDatabaseManager;
        this.leaderboardManager = leaderboardManager;
        this.chiselLives = chiselLives;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("website-sync.enabled", true)) {
            return;
        }

        String host = plugin.getConfig().getString("website-sync.host", "0.0.0.0");
        int port = plugin.getConfig().getInt("website-sync.port", 52517);
        this.basePath = normalizePath(plugin.getConfig().getString("website-sync.base-path", "/sync"));
        this.corsAllowOrigin = plugin.getConfig().getString("website-sync.cors-allow-origin", "*");
        this.purchaseSecretHeader = plugin.getConfig().getString("website-sync.purchase-secret-header", "X-Chisel-Store-Secret");
        this.purchaseSecret = plugin.getConfig().getString("website-sync.purchase-secret", "");
        if (this.purchaseSecret == null) {
            this.purchaseSecret = "";
        }
        this.purchaseSecret = this.purchaseSecret.trim();
        this.purchaseEndpointEnabled = !this.purchaseSecret.isBlank();

        stop();

        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext(resolvePath("/health"), this::handleHealth);
            server.createContext(resolvePath("/store/player"), this::handleStorePlayer);
            server.createContext(resolvePath("/store/purchase"), this::handleStorePurchase);
            server.createContext(resolvePath("/leaderboard/players"), this::handlePlayerLeaderboard);
            server.createContext(resolvePath("/leaderboard/kingdoms"), this::handleKingdomLeaderboard);
            server.setExecutor(Executors.newCachedThreadPool(new SyncThreadFactory()));
            server.start();

            if (!purchaseEndpointEnabled) {
                plugin.getLogger().warning("[WebsiteSync] Purchase endpoint is disabled because website-sync.purchase-secret is empty.");
            }

            plugin.getLogger().info("[WebsiteSync] API started on " + host + ":" + port + basePath);
        } catch (IOException exception) {
            plugin.getLogger().warning("[WebsiteSync] Failed to start API server: " + exception.getMessage());
            server = null;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        sendJson(exchange, 200, "{\"ok\":true,\"service\":\"chiselmod-sync\",\"purchaseEndpointEnabled\":" + purchaseEndpointEnabled + "}");
    }

    private void handleStorePlayer(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String username = firstPresent(query, "username", "player", "player_name");
        if (username == null || username.isBlank()) {
            sendJson(exchange, 400, "{\"ok\":false,\"message\":\"Missing username query parameter.\"}");
            return;
        }

        username = username.trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            sendJson(exchange, 400, "{\"ok\":false,\"message\":\"Invalid username format.\"}");
            return;
        }

        UUID uuid = bountyDatabaseManager.findPlayerUuidByName(username);
        if (uuid == null) {
            sendJson(exchange, 404, "{\"ok\":false,\"exists\":false,\"message\":\"Player was not found in server database.\"}");
            return;
        }

        LivesManager livesManager = chiselLives.getLivesManager();
        if (livesManager == null) {
            sendJson(exchange, 503, "{\"ok\":false,\"message\":\"ChiselLives is not available.\"}");
            return;
        }

        try {
            com.chisellives.DatabaseManager.PlayerRecord record = livesManager
                    .getPlayerRecord(uuid, username)
                    .get(10, TimeUnit.SECONDS);
            Rank rank = plugin.getDatabaseManager().loadPlayerRankSync(uuid);
            String resolvedName = record.username() == null || record.username().isBlank() ? username : record.username();

            String body = "{"
                    + "\"ok\":true,"
                    + "\"exists\":true,"
                    + "\"player\":{"
                    + "\"uuid\":\"" + uuid + "\"," 
                    + "\"username\":\"" + escapeJson(resolvedName) + "\"," 
                    + "\"lives\":" + record.lives() + ","
                    + "\"banned\":" + record.banned() + ","
                    + "\"rank\":\"" + escapeJson(rank.getKey()) + "\"," 
                    + "\"rankDisplay\":\"" + escapeJson(rank.getDisplayName()) + "\"," 
                    + "\"maxLives\":" + livesManager.getMaxLives() + ","
                    + "\"startingLives\":" + livesManager.getStartingLives()
                    + "}"
                    + "}";

            sendJson(exchange, 200, body);
        } catch (Exception exception) {
            sendJson(exchange, 500, "{\"ok\":false,\"message\":\"Failed to load player profile: " + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private void handleStorePurchase(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        if (!purchaseEndpointEnabled) {
            sendJson(exchange, 503, "{\"ok\":false,\"message\":\"Store purchase endpoint is disabled. Set website-sync.purchase-secret in plugin config.\"}");
            return;
        }

        if (!isPurchaseAuthorized(exchange.getRequestHeaders())) {
            sendJson(exchange, 401, "{\"ok\":false,\"message\":\"Invalid store purchase secret.\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = parseBody(body, exchange.getRequestHeaders().getFirst("Content-Type"));

        StoreProduct product = resolveProduct(firstPresent(values, "product", "type", "item"));
        if (product == null) {
            sendJson(exchange, 400, "{\"ok\":false,\"message\":\"Unsupported product type.\"}");
            return;
        }

        UUID uuid = resolveUuid(values);
        String username = firstPresent(values, "username", "player_name", "player", "name");
        if (uuid == null && username != null && !username.isBlank()) {
            String normalizedUsername = username.trim();
            uuid = bountyDatabaseManager.findPlayerUuidByName(normalizedUsername);
        }

        if (uuid == null) {
            sendJson(exchange, 404, "{\"ok\":false,\"message\":\"Could not resolve player UUID from payload.\"}");
            return;
        }

        if (product.rank() != null) {
            handleRankPurchase(exchange, uuid, product);
            return;
        }

        handleLivesPurchase(exchange, uuid, username, product);
    }

    private void handleRankPurchase(HttpExchange exchange, UUID uuid, StoreProduct product) throws IOException {
        CompletableFuture<Rank> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Rank applied = plugin.getRankManager().grantRank(uuid, product.rank(), RankManager.GrantAction.GRANT);
            future.complete(applied);
        });

        try {
            Rank applied = future.get(10, TimeUnit.SECONDS);
            String response = "{"
                    + "\"ok\":true,"
                    + "\"kind\":\"rank\","
                    + "\"rank\":\"" + escapeJson(applied.getKey()) + "\"," 
                    + "\"message\":\"Rank " + escapeJson(applied.getDisplayName()) + " applied in-game.\""
                    + "}";
            sendJson(exchange, 200, response);
        } catch (Exception exception) {
            sendJson(exchange, 500, "{\"ok\":false,\"message\":\"Failed to apply rank purchase: " + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private void handleLivesPurchase(HttpExchange exchange, UUID uuid, String username, StoreProduct product) throws IOException {
        LivesManager livesManager = chiselLives.getLivesManager();
        if (livesManager == null) {
            sendJson(exchange, 503, "{\"ok\":false,\"message\":\"ChiselLives is not available.\"}");
            return;
        }

        com.chisellives.DatabaseManager.PurchaseRecord purchaseRecord =
                new com.chisellives.DatabaseManager.PurchaseRecord(-1L, uuid, product.livesType());

        try {
            LivesManager.PurchaseOutcome outcome = livesManager.applyPurchase(purchaseRecord).get(10, TimeUnit.SECONDS);
            if (!"processed".equalsIgnoreCase(outcome.purchaseStatus())) {
                sendJson(exchange, 409, "{\"ok\":false,\"message\":\"Purchase was cancelled. Player may already be at max lives or not eligible.\"}");
                return;
            }

            String fallbackName = username == null || username.isBlank() ? uuid.toString() : username;
            com.chisellives.DatabaseManager.PlayerRecord record = livesManager
                    .getPlayerRecord(uuid, fallbackName)
                    .get(10, TimeUnit.SECONDS);

            String response = "{"
                    + "\"ok\":true,"
                    + "\"kind\":\"lives\","
                    + "\"lives\":" + record.lives() + ","
                    + "\"maxLives\":" + livesManager.getMaxLives() + ","
                    + "\"message\":\"Lives purchase applied in-game.\""
                    + "}";
            sendJson(exchange, 200, response);
        } catch (Exception exception) {
            sendJson(exchange, 500, "{\"ok\":false,\"message\":\"Failed to apply lives purchase: " + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private void handlePlayerLeaderboard(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        try {
            List<LeaderboardEntry> entries = leaderboardManager.getTopPlayers().get(10, TimeUnit.SECONDS);
            sendJson(exchange, 200, toPlayerLeaderboardJson(entries));
        } catch (Exception exception) {
            sendJson(exchange, 500, "{\"ok\":false,\"message\":\"Failed to load player leaderboard: " + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private void handleKingdomLeaderboard(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        try {
            List<LeaderboardEntry> entries = leaderboardManager.getTopKingdoms().get(10, TimeUnit.SECONDS);
            sendJson(exchange, 200, toKingdomLeaderboardJson(entries));
        } catch (Exception exception) {
            sendJson(exchange, 500, "{\"ok\":false,\"message\":\"Failed to load kingdom leaderboard: " + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private String toPlayerLeaderboardJson(List<LeaderboardEntry> entries) {
        com.beyondminer.leaderboards.managers.DatabaseManager leaderboardDb = plugin.getLeaderboardDatabaseManager();
        SkinHeadService skinHeadService = plugin.getSkinHeadService();
        List<String> payload = new ArrayList<>();
        for (LeaderboardEntry entry : entries) {
            if (entry == null || entry.name() == null || entry.name().isBlank()) {
                continue;
            }

            StringBuilder row = new StringBuilder();
            row.append("{\"name\":\"")
                    .append(escapeJson(entry.name()))
                    .append("\",\"bounty\":")
                    .append(entry.bounty());

            Optional<String> skinUrl = Optional.empty();
            UUID playerUuid = bountyDatabaseManager.findPlayerUuidByName(entry.name());
            if (leaderboardDb != null) {
                skinUrl = leaderboardDb.findSkinUrl(entry.name());
                if (skinUrl.isEmpty() && playerUuid != null) {
                    skinUrl = leaderboardDb.findSkinUrl(playerUuid.toString());
                }
            }

            if (skinUrl.isEmpty() && skinHeadService != null) {
                skinUrl = skinHeadService.resolveSkinTextureUrl(entry.name(), true);
                if (skinUrl.isEmpty() && playerUuid != null) {
                    skinUrl = skinHeadService.resolveSkinTextureUrl(playerUuid.toString(), true);
                }
                if (skinUrl.isPresent() && leaderboardDb != null) {
                    if (playerUuid != null) {
                        leaderboardDb.upsertPlayerSkin(playerUuid, entry.name(), skinUrl.get());
                    }
                }
            }

            skinUrl.ifPresent(url -> row.append(",\"skinUrl\":\"").append(escapeJson(url)).append("\""));

            row.append('}');
            payload.add(row.toString());
        }
        return "[" + String.join(",", payload) + "]";
    }

    private String toKingdomLeaderboardJson(List<LeaderboardEntry> entries) {
        List<String> payload = new ArrayList<>();
        for (LeaderboardEntry entry : entries) {
            if (entry == null || entry.name() == null || entry.name().isBlank()) {
                continue;
            }
            payload.add("{\"name\":\"" + escapeJson(entry.name()) + "\",\"bounty\":" + entry.bounty() + "}");
        }
        return "[" + String.join(",", payload) + "]";
    }

    private UUID resolveUuid(Map<String, String> values) {
        String uuidText = firstPresent(values, "uuid", "player_uuid");
        if (uuidText == null || uuidText.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(uuidText.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private StoreProduct resolveProduct(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');

        return switch (normalized) {
            case "1_life", "1_lives", "life_1", "1", "1life" -> new StoreProduct(null, "1 life");
            case "5_lives", "5_life", "life_5", "5", "5lives" -> new StoreProduct(null, "5 lives");
            case "10_lives", "10_life", "life_10", "10", "10lives" -> new StoreProduct(null, "10 lives");
            case "revival", "revive" -> new StoreProduct(null, "revival");
            case "revival_totem", "totem" -> new StoreProduct(null, "revival_totem");
            case "rank_gold", "gold" -> new StoreProduct(Rank.GOLD, null);
            case "rank_diamond", "diamond" -> new StoreProduct(Rank.DIAMOND, null);
            case "rank_netherite", "netherite" -> new StoreProduct(Rank.NETHERITE, null);
            default -> null;
        };
    }

    private boolean isPurchaseAuthorized(Headers headers) {
        if (!purchaseEndpointEnabled) {
            return false;
        }

        if (purchaseSecretHeader == null || purchaseSecretHeader.isBlank()) {
            return false;
        }

        String provided = headers.getFirst(purchaseSecretHeader);
        return purchaseSecret.equals(provided);
    }

    private boolean handlePreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }

        Headers headers = exchange.getResponseHeaders();
        addCorsHeaders(headers);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        sendJson(exchange, 405, "{\"ok\":false,\"message\":\"Method not allowed.\"}");
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        addCorsHeaders(headers);
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private void addCorsHeaders(Headers headers) {
        String allowOrigin = corsAllowOrigin == null || corsAllowOrigin.isBlank() ? "*" : corsAllowOrigin;
        headers.set("Access-Control-Allow-Origin", allowOrigin);
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Accept, " + safeHeaderName(purchaseSecretHeader));
        headers.set("Access-Control-Max-Age", "600");
    }

    private String safeHeaderName(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return "X-Chisel-Store-Secret";
        }
        return headerName;
    }

    private Map<String, String> parseBody(String body, String contentType) {
        Map<String, String> values = new HashMap<>();
        if (body == null || body.isBlank()) {
            return values;
        }

        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("x-www-form-urlencoded")) {
            return parseQuery(body);
        }

        Matcher matcher = JSON_FIELD.matcher(body);
        while (matcher.find()) {
            String raw = matcher.group(3) != null ? matcher.group(3) : matcher.group(2);
            values.put(matcher.group(1).toLowerCase(Locale.ROOT), raw);
        }

        return values;
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }

        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isBlank()) {
                continue;
            }

            String[] split = pair.split("=", 2);
            String key = URLDecoder.decode(split[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            String value = split.length > 1 ? URLDecoder.decode(split[1], StandardCharsets.UTF_8) : "";
            query.put(key, value);
        }

        return query;
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

    private String normalizePath(String input) {
        if (input == null || input.isBlank()) {
            return "/sync";
        }

        String path = input.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    private String resolvePath(String suffix) {
        if ("/".equals(basePath)) {
            return suffix;
        }

        return basePath + suffix;
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }

        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private record StoreProduct(Rank rank, String livesType) {
    }

    private static final class SyncThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ChiselMod-WebSync");
            thread.setDaemon(true);
            return thread;
        }
    }
}
