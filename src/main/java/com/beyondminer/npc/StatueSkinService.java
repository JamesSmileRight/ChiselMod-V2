package com.beyondminer.npc;

import com.beyondminer.skin.SkinHeadService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public final class StatueSkinService {

    private static final String USER_AGENT = "KingdomsBounty/1.0";

    private final Executor asyncExecutor;
    private final JavaPlugin plugin;
    private final SkinHeadService skinHeadService;
    private final Map<String, ResolvedSkin> cache = new ConcurrentHashMap<>();

    public StatueSkinService(JavaPlugin plugin, SkinHeadService skinHeadService) {
        this.plugin = plugin;
        this.asyncExecutor = task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        this.skinHeadService = skinHeadService;
    }

    public CompletableFuture<ResolvedSkin> resolvePremium(String username) {
        String lookup = normalize(username);
        if (lookup.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        ResolvedSkin cached = cache.get("premium:" + lookup);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> fetchPremiumSkin(username), asyncExecutor)
                .thenApply(resolved -> cacheResolved("premium:" + lookup, resolved));
    }

    public CompletableFuture<ResolvedSkin> resolveOffline(String skinNameOrUuid) {
        String lookup = normalize(skinNameOrUuid);
        if (lookup.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        ResolvedSkin cached = cache.get("offline:" + lookup);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            if (skinHeadService != null) {
                var property = skinHeadService.resolveSkinProperty(skinNameOrUuid, true);
                if (property.isPresent()) {
                    SkinHeadService.SkinPropertyData skinProperty = property.get();
                    return new ResolvedSkin(
                            skinProperty.value(),
                            skinProperty.signature(),
                            skinProperty.lookup(),
                            SkinSourceType.OFFLINE
                    );
                }

                ResolvedSkin resolved = skinHeadService.resolveSkinTextureUrl(skinNameOrUuid, true)
                        .map(url -> new ResolvedSkin(encodeTextureUrl(url), "", skinNameOrUuid, SkinSourceType.OFFLINE))
                        .orElse(null);
                if (resolved != null) {
                    return resolved;
                }
            }

            return fetchPremiumSkin(skinNameOrUuid);
        }, asyncExecutor).thenApply(resolved -> cacheResolved("offline:" + lookup, resolved));
    }

    private ResolvedSkin fetchPremiumSkin(String username) {
        try {
            String uuid = fetchUuid(username);
            if (uuid == null) {
                return null;
            }

            return fetchTextures(uuid, username);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String fetchUuid(String username) throws Exception {
        URI uri = URI.create("https://api.mojang.com/users/profiles/minecraft/" + username);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        if (connection.getResponseCode() != 200) {
            return null;
        }

        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            return object.has("id") ? object.get("id").getAsString() : null;
        }
    }

    private ResolvedSkin fetchTextures(String uuidWithoutHyphens, String username) throws Exception {
        URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"
                + uuidWithoutHyphens.replace("-", "") + "?unsigned=false");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        if (connection.getResponseCode() != 200) {
            return null;
        }

        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray properties = object.has("properties") ? object.getAsJsonArray("properties") : null;
            if (properties == null || properties.isEmpty()) {
                return null;
            }

            for (int index = 0; index < properties.size(); index++) {
                JsonObject property = properties.get(index).getAsJsonObject();
                if (!property.has("name") || !Objects.equals(property.get("name").getAsString(), "textures")) {
                    continue;
                }

                String value = property.has("value") ? property.get("value").getAsString() : "";
                String signature = property.has("signature") ? property.get("signature").getAsString() : "";
                if (!value.isBlank()) {
                    return new ResolvedSkin(value, signature, username, SkinSourceType.PREMIUM);
                }
            }

            return null;
        }
    }

    private String encodeTextureUrl(String url) {
        String payload = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private ResolvedSkin cacheResolved(String key, ResolvedSkin resolved) {
        if (resolved != null) {
            cache.put(key, resolved);
        }
        return resolved;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public enum SkinSourceType {
        PREMIUM,
        OFFLINE
    }

    public record ResolvedSkin(String value, String signature, String lookup, SkinSourceType sourceType) {
    }
}