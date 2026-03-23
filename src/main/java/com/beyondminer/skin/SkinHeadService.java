package com.beyondminer.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

public final class SkinHeadService {

    private static final long CACHE_TTL_MILLIS = 30L * 60L * 1000L;

    private final Executor asyncExecutor;
    private final ConcurrentMap<String, CachedTexture> textureCache = new ConcurrentHashMap<>();
    private final SkinsRestorerApiBridge skinsRestorerBridge;

    public SkinHeadService(JavaPlugin plugin) {
        this.asyncExecutor = runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        this.skinsRestorerBridge = SkinsRestorerApiBridge.tryCreate(plugin);
    }

    public boolean isSkinsRestorerAvailable() {
        return skinsRestorerBridge != null;
    }

    public Optional<SkinPropertyData> resolveSkinProperty(String skinNameOrUuid, boolean createIfMissing) {
        String key = normalizeKey(skinNameOrUuid);
        if (key.isBlank()) {
            return Optional.empty();
        }

        UUID inputUuid = parseUuid(skinNameOrUuid);
        if (skinsRestorerBridge != null) {
            Optional<SkinPropertyData> stored = skinsRestorerBridge.lookupSkinPropertyForPlayer(skinNameOrUuid, inputUuid, false);
            if (stored.isPresent()) {
                SkinPropertyData property = stored.get();
                cacheResolvedProperty(key, inputUuid, property);
                return Optional.of(property);
            }

            if (createIfMissing) {
                Optional<SkinPropertyData> created = skinsRestorerBridge.lookupSkinPropertyForPlayer(skinNameOrUuid, inputUuid, true);
                if (created.isPresent()) {
                    SkinPropertyData property = created.get();
                    cacheResolvedProperty(key, inputUuid, property);
                    return Optional.of(property);
                }
            }
        }

        Player onlinePlayer = findOnlinePlayer(skinNameOrUuid);
        if (onlinePlayer != null) {
            Optional<SkinPropertyData> onlineProperty = resolveSkinPropertyFromOnlineProfile(onlinePlayer);
            if (onlineProperty.isPresent()) {
                SkinPropertyData property = onlineProperty.get();
                cacheResolvedProperty(key, onlinePlayer.getUniqueId(), property);
                return Optional.of(property);
            }
        }

        return Optional.empty();
    }

    public Optional<String> resolveSkinTextureUrl(String skinNameOrUuid, boolean createIfMissing) {
        String key = normalizeKey(skinNameOrUuid);
        if (key.isBlank()) {
            return Optional.empty();
        }

        String cachedTextureUrl = getCachedTextureUrl(key);
        if (cachedTextureUrl != null) {
            return Optional.of(cachedTextureUrl);
        }

        UUID inputUuid = parseUuid(skinNameOrUuid);
        Player onlinePlayer = findOnlinePlayer(skinNameOrUuid);
        if (skinsRestorerBridge != null) {
            Optional<String> stored = skinsRestorerBridge.lookupTextureUrlForPlayer(skinNameOrUuid, inputUuid, false);
            if (stored.isPresent()) {
                String textureUrl = stored.get();
                cacheTexture(key, textureUrl);
                if (onlinePlayer != null) {
                    cacheTextureAliases(onlinePlayer, textureUrl);
                } else if (inputUuid != null) {
                    cacheTexture(inputUuid.toString().toLowerCase(Locale.ROOT), textureUrl);
                }
                return Optional.of(textureUrl);
            }

            if (createIfMissing) {
                Optional<String> created = skinsRestorerBridge.lookupTextureUrlForPlayer(skinNameOrUuid, inputUuid, true);
                if (created.isPresent()) {
                    String textureUrl = created.get();
                    cacheTexture(key, textureUrl);
                    if (onlinePlayer != null) {
                        cacheTextureAliases(onlinePlayer, textureUrl);
                    } else if (inputUuid != null) {
                        cacheTexture(inputUuid.toString().toLowerCase(Locale.ROOT), textureUrl);
                    }
                    return Optional.of(textureUrl);
                }
            }
        }

        if (onlinePlayer != null) {
            Optional<String> profileTextureUrl = resolveTextureFromOnlineProfile(onlinePlayer);
            if (profileTextureUrl.isPresent()) {
                String textureUrl = profileTextureUrl.get();
                cacheTexture(key, textureUrl);
                cacheTextureAliases(onlinePlayer, textureUrl);
                return Optional.of(textureUrl);
            }
        }

        return Optional.empty();
    }

    public Optional<String> resolveSkinTextureUrl(Player player, boolean createIfMissing) {
        if (player == null) {
            return Optional.empty();
        }

        Optional<String> profileTextureUrl = resolveTextureFromOnlineProfile(player);
        if (profileTextureUrl.isPresent()) {
            String textureUrl = profileTextureUrl.get();
            cacheTextureAliases(player, textureUrl);
            return Optional.of(textureUrl);
        }

        Optional<String> byName = resolveSkinTextureUrl(player.getName(), createIfMissing);
        if (byName.isPresent()) {
            cacheTextureAliases(player, byName.get());
            return byName;
        }

        Optional<String> byUuid = resolveSkinTextureUrl(player.getUniqueId().toString(), createIfMissing);
        byUuid.ifPresent(url -> cacheTextureAliases(player, url));
        return byUuid;
    }

    public CompletableFuture<Void> prefetchSkin(String skinNameOrUuid) {
        String key = normalizeKey(skinNameOrUuid);
        if (key.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        if (getCachedTextureUrl(key) != null || skinsRestorerBridge == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            resolveSkinTextureUrl(skinNameOrUuid, true);
        }, asyncExecutor).exceptionally(throwable -> null);
    }

    public ItemStack createHead(String skinNameOrUuid, String displayName) {
        return createHeadInternal(skinNameOrUuid, displayName, true);
    }

    public ItemStack createHeadFromCache(String skinNameOrUuid, String displayName) {
        return createHeadInternal(skinNameOrUuid, displayName, false);
    }

    public ItemStack createHeadFromTextureData(String textureValue, String displayName) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        URL textureUrl = decodeTextureUrl(textureValue);
        if (textureUrl != null) {
            UUID profileUuid = UUID.nameUUIDFromBytes(("kingdomsbounty-texture:" + textureValue).getBytes(StandardCharsets.UTF_8));
            Object profile = createProfileWithSkin(profileUuid, textureUrl);
            if (profile != null) {
                applyProfileToMeta(meta, profile);
            }
        }

        if (displayName != null && !displayName.isBlank()) {
            meta.displayName(Component.text(displayName, NamedTextColor.YELLOW));
        }

        head.setItemMeta(meta);
        return head;
    }

    private ItemStack createHeadInternal(String skinNameOrUuid, String displayName, boolean allowCreateLookup) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        applyBestAvailableSkin(meta, skinNameOrUuid, allowCreateLookup);

        if (displayName != null && !displayName.isBlank()) {
            meta.displayName(Component.text(displayName, NamedTextColor.YELLOW));
        }

        head.setItemMeta(meta);
        return head;
    }

    private void applyBestAvailableSkin(SkullMeta meta, String skinNameOrUuid, boolean allowCreateLookup) {
        String key = normalizeKey(skinNameOrUuid);
        if (key.isBlank()) {
            return;
        }

        Player onlinePlayer = findOnlinePlayer(skinNameOrUuid);
        if (onlinePlayer != null) {
            applyOnlinePlayerSkin(meta, onlinePlayer, key);
            return;
        }

        String cachedTextureUrl = getCachedTextureUrl(key);
        if (cachedTextureUrl == null) {
            cachedTextureUrl = getCachedTextureUrlByAlias(key, skinNameOrUuid);
        }
        if (cachedTextureUrl != null && applyTextureUrl(meta, key, cachedTextureUrl)) {
            return;
        }

        if (skinsRestorerBridge != null) {
            Optional<String> skinStorageUrl = skinsRestorerBridge.lookupTextureUrl(skinNameOrUuid, false);
            if (skinStorageUrl.isPresent() && applyTextureUrl(meta, key, skinStorageUrl.get())) {
                return;
            }

            if (allowCreateLookup) {
                Optional<String> createdUrl = skinsRestorerBridge.lookupTextureUrl(skinNameOrUuid, true);
                if (createdUrl.isPresent() && applyTextureUrl(meta, key, createdUrl.get())) {
                    return;
                }
            }
        }

        applyOfflineFallback(meta, skinNameOrUuid);
    }

    private void applyOnlinePlayerSkin(SkullMeta meta, Player onlinePlayer, String key) {
        boolean profileApplied = false;
        Optional<String> onlineTextureUrl = Optional.empty();

        try {
            Object onlineProfile = onlinePlayer.getClass().getMethod("getPlayerProfile").invoke(onlinePlayer);
            if (onlineProfile != null) {
                profileApplied = applyProfileToMeta(meta, onlineProfile);
                onlineTextureUrl = getTextureUrlFromProfile(onlineProfile);
            }
        } catch (Exception ignored) {
            profileApplied = false;
        }

        if (!profileApplied) {
            meta.setOwningPlayer(onlinePlayer);
        }

        onlineTextureUrl.ifPresent(url -> {
            cacheTexture(key, url);
            cacheTextureAliases(onlinePlayer, url);
        });
    }

    private Optional<String> resolveTextureFromOnlineProfile(Player onlinePlayer) {
        if (onlinePlayer == null) {
            return Optional.empty();
        }

        try {
            Object onlineProfile = onlinePlayer.getClass().getMethod("getPlayerProfile").invoke(onlinePlayer);
            Optional<SkinPropertyData> property = extractSkinPropertyData(onlineProfile, onlinePlayer.getName());
            if (property.isPresent() && property.get().textureUrl() != null && !property.get().textureUrl().isBlank()) {
                return Optional.of(property.get().textureUrl());
            }
            return getTextureUrlFromProfile(onlineProfile);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<SkinPropertyData> resolveSkinPropertyFromOnlineProfile(Player onlinePlayer) {
        if (onlinePlayer == null) {
            return Optional.empty();
        }

        try {
            Object onlineProfile = onlinePlayer.getClass().getMethod("getPlayerProfile").invoke(onlinePlayer);
            Optional<SkinPropertyData> property = extractSkinPropertyData(onlineProfile, onlinePlayer.getName());
            property.ifPresent(data -> cacheResolvedProperty(onlinePlayer.getName().toLowerCase(Locale.ROOT), onlinePlayer.getUniqueId(), data));
            return property;
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private void cacheTextureAliases(Player player, String textureUrl) {
        if (player == null || textureUrl == null || textureUrl.isBlank()) {
            return;
        }

        cacheTexture(player.getName().toLowerCase(Locale.ROOT), textureUrl);
        cacheTexture(player.getUniqueId().toString().toLowerCase(Locale.ROOT), textureUrl);
    }

    private Player findOnlinePlayer(String skinNameOrUuid) {
        UUID uuid = parseUuid(skinNameOrUuid);
        if (uuid != null) {
            return Bukkit.getPlayer(uuid);
        }

        return Bukkit.getPlayerExact(skinNameOrUuid);
    }

    private void applyOfflineFallback(SkullMeta meta, String skinNameOrUuid) {
        UUID uuid = parseUuid(skinNameOrUuid);
        if (uuid != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            meta.setOwningPlayer(offlinePlayer);
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(skinNameOrUuid);
        meta.setOwningPlayer(offlinePlayer);
    }

    private boolean applyTextureUrl(SkullMeta meta, String key, String textureUrl) {
        if (textureUrl == null || textureUrl.isBlank()) {
            return false;
        }

        try {
            URL url = URI.create(textureUrl).toURL();
            UUID profileUuid = UUID.nameUUIDFromBytes(("kingdomsbounty-skin:" + key).getBytes(StandardCharsets.UTF_8));
            Object profile = createProfileWithSkin(profileUuid, url);
            if (profile == null || !applyProfileToMeta(meta, profile)) {
                return false;
            }
            cacheTexture(key, textureUrl);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Optional<String> getTextureUrlFromProfile(Object profile) {
        if (profile == null) {
            return Optional.empty();
        }

        try {
            Object textures = profile.getClass().getMethod("getTextures").invoke(profile);
            if (textures == null) {
                return Optional.empty();
            }

            Object skinValue = textures.getClass().getMethod("getSkin").invoke(textures);
            if (skinValue instanceof URL url) {
                return Optional.of(url.toExternalForm());
            }

            if (skinValue instanceof String raw && !raw.isBlank()) {
                return Optional.of(raw);
            }

            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<SkinPropertyData> extractSkinPropertyData(Object profile, String lookup) {
        if (profile == null) {
            return Optional.empty();
        }

        try {
            Method getPropertiesMethod = profile.getClass().getMethod("getProperties");
            Object propertiesObject = getPropertiesMethod.invoke(profile);
            if (!(propertiesObject instanceof Iterable<?> properties)) {
                return Optional.empty();
            }

            for (Object property : properties) {
                if (property == null) {
                    continue;
                }

                String name = extractString(property, "getName", "name");
                if (!"textures".equalsIgnoreCase(name)) {
                    continue;
                }

                String value = extractString(property, "getValue", "value");
                if (value == null || value.isBlank()) {
                    continue;
                }

                String signature = extractString(property, "getSignature", "signature");
                URL textureUrl = decodeTextureUrl(value);
                return Optional.of(new SkinPropertyData(
                        value,
                        signature == null ? "" : signature,
                        textureUrl == null ? "" : textureUrl.toExternalForm(),
                        lookup == null ? "" : lookup
                ));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private URL decodeTextureUrl(String textureValue) {
        if (textureValue == null || textureValue.isBlank()) {
            return null;
        }

        try {
            String json = new String(Base64.getDecoder().decode(textureValue), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = root.has("textures") ? root.getAsJsonObject("textures") : null;
            JsonObject skin = textures != null && textures.has("SKIN") ? textures.getAsJsonObject("SKIN") : null;
            if (skin == null || !skin.has("url")) {
                return null;
            }
            return URI.create(skin.get("url").getAsString()).toURL();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object createProfileWithSkin(UUID profileUuid, URL skinUrl) {
        try {
            Object profile = createPlayerProfile(profileUuid);
            if (profile == null) {
                return null;
            }

            Object textures = profile.getClass().getMethod("getTextures").invoke(profile);
            if (textures == null) {
                return null;
            }

            Method setSkinMethod = findCompatibleOneArgMethod(textures.getClass(), "setSkin", URL.class);
            if (setSkinMethod == null) {
                return null;
            }
            setSkinMethod.invoke(textures, skinUrl);

            Method setTexturesMethod = findCompatibleOneArgMethod(profile.getClass(), "setTextures", textures.getClass());
            if (setTexturesMethod != null) {
                setTexturesMethod.invoke(profile, textures);
            }

            return profile;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object createPlayerProfile(UUID profileUuid) {
        try {
            return Bukkit.class.getMethod("createPlayerProfile", UUID.class).invoke(null, profileUuid);
        } catch (NoSuchMethodException ignored) {
            try {
                return Bukkit.class.getMethod("createPlayerProfile", UUID.class, String.class).invoke(null, profileUuid, null);
            } catch (Exception ignoredAgain) {
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean applyProfileToMeta(SkullMeta meta, Object profile) {
        if (meta == null || profile == null) {
            return false;
        }

        try {
            Method setPlayerProfile = findCompatibleOneArgMethod(meta.getClass(), "setPlayerProfile", profile.getClass());
            if (setPlayerProfile != null) {
                setPlayerProfile.invoke(meta, profile);
                return true;
            }

            Method setOwnerProfile = findCompatibleOneArgMethod(meta.getClass(), "setOwnerProfile", profile.getClass());
            if (setOwnerProfile != null) {
                setOwnerProfile.invoke(meta, profile);
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }

        return false;
    }

    private Method findCompatibleOneArgMethod(Class<?> type, String methodName, Class<?> argType) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }

            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType.isAssignableFrom(argType)) {
                return method;
            }
        }

        return null;
    }

    private String extractString(Object target, String accessorName, String fallbackAccessorName) {
        if (target == null) {
            return null;
        }

        try {
            Object value = target.getClass().getMethod(accessorName).invoke(target);
            return value instanceof String stringValue ? stringValue : null;
        } catch (Exception ignored) {
        }

        try {
            Object value = target.getClass().getMethod(fallbackAccessorName).invoke(target);
            return value instanceof String stringValue ? stringValue : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getCachedTextureUrl(String key) {
        CachedTexture cachedTexture = textureCache.get(key);
        if (cachedTexture == null) {
            return null;
        }

        if (System.currentTimeMillis() - cachedTexture.cachedAtMillis > CACHE_TTL_MILLIS) {
            textureCache.remove(key, cachedTexture);
            return null;
        }

        return cachedTexture.textureUrl;
    }

    private String getCachedTextureUrlByAlias(String key, String skinNameOrUuid) {
        if (key.isBlank() || skinNameOrUuid == null || skinNameOrUuid.isBlank()) {
            return null;
        }

        UUID uuid = parseUuid(skinNameOrUuid);
        if (uuid == null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(skinNameOrUuid);
            if (offlinePlayer != null) {
                uuid = offlinePlayer.getUniqueId();
            }
        }

        if (uuid == null) {
            return null;
        }

        String uuidKey = uuid.toString().toLowerCase(Locale.ROOT);
        String cachedTextureUrl = getCachedTextureUrl(uuidKey);
        if (cachedTextureUrl != null) {
            cacheTexture(key, cachedTextureUrl);
        }
        return cachedTextureUrl;
    }

    private void cacheTexture(String key, String textureUrl) {
        if (key == null || key.isBlank() || textureUrl == null || textureUrl.isBlank()) {
            return;
        }

        textureCache.put(key, new CachedTexture(textureUrl, System.currentTimeMillis()));
    }

    private void cacheResolvedProperty(String key, UUID inputUuid, SkinPropertyData property) {
        if (property == null || property.textureUrl() == null || property.textureUrl().isBlank()) {
            return;
        }

        cacheTexture(key, property.textureUrl());
        if (inputUuid != null) {
            cacheTexture(inputUuid.toString().toLowerCase(Locale.ROOT), property.textureUrl());
        }
    }

    private String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }

        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private UUID parseUuid(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(input.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record CachedTexture(String textureUrl, long cachedAtMillis) {
    }

    public record SkinPropertyData(String value, String signature, String textureUrl, String lookup) {
    }

    private static final class SkinsRestorerApiBridge {

        private final Object skinsRestorerApi;
        private final Method getSkinStorageMethod;
        private final Method findSkinDataMethod;
        private final Method findOrCreateSkinDataMethod;
        private final Method getPropertyMethod;
        private final Method getSkinTextureUrlMethod;
        private final Method getPlayerStorageMethod;
        private final Method findSkinOfPlayerMethod;

        private SkinsRestorerApiBridge(
                Object skinsRestorerApi,
                Method getSkinStorageMethod,
                Method findSkinDataMethod,
                Method findOrCreateSkinDataMethod,
                Method getPropertyMethod,
                Method getSkinTextureUrlMethod,
                Method getPlayerStorageMethod,
                Method findSkinOfPlayerMethod
        ) {
            this.skinsRestorerApi = skinsRestorerApi;
            this.getSkinStorageMethod = getSkinStorageMethod;
            this.findSkinDataMethod = findSkinDataMethod;
            this.findOrCreateSkinDataMethod = findOrCreateSkinDataMethod;
            this.getPropertyMethod = getPropertyMethod;
            this.getSkinTextureUrlMethod = getSkinTextureUrlMethod;
            this.getPlayerStorageMethod = getPlayerStorageMethod;
            this.findSkinOfPlayerMethod = findSkinOfPlayerMethod;
        }

        private static SkinsRestorerApiBridge tryCreate(JavaPlugin plugin) {
            boolean pluginEnabled = plugin.getServer().getPluginManager().isPluginEnabled("SkinsRestorer")
                    || plugin.getServer().getPluginManager().isPluginEnabled("SkinRestorer");
            if (!pluginEnabled) {
                return null;
            }

            try {
                Class<?> providerClass = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider");
                Object api = providerClass.getMethod("get").invoke(null);

                Class<?> skinsRestorerClass = Class.forName("net.skinsrestorer.api.SkinsRestorer");
                Method getSkinStorage = skinsRestorerClass.getMethod("getSkinStorage");

                Class<?> skinStorageClass = Class.forName("net.skinsrestorer.api.storage.SkinStorage");
                Method findOrCreate = skinStorageClass.getMethod("findOrCreateSkinData", String.class);

                Method findSkinData;
                try {
                    findSkinData = skinStorageClass.getMethod("findSkinData", String.class);
                } catch (NoSuchMethodException ignored) {
                    findSkinData = null;
                }

                Class<?> inputDataResultClass = Class.forName("net.skinsrestorer.api.property.InputDataResult");
                Method getProperty = inputDataResultClass.getMethod("getProperty");

                Class<?> propertyUtilsClass = Class.forName("net.skinsrestorer.api.PropertyUtils");
                Class<?> skinPropertyClass = Class.forName("net.skinsrestorer.api.property.SkinProperty");
                Method getTextureUrl = propertyUtilsClass.getMethod("getSkinTextureUrl", skinPropertyClass);

                Method getPlayerStorage;
                try {
                    getPlayerStorage = skinsRestorerClass.getMethod("getPlayerStorage");
                } catch (NoSuchMethodException ignored) {
                    getPlayerStorage = null;
                }

                Method findSkinOfPlayer = null;
                if (getPlayerStorage != null) {
                    try {
                        Class<?> playerStorageClass = Class.forName("net.skinsrestorer.api.storage.PlayerStorage");
                        findSkinOfPlayer = findOptionalLookupMethod(playerStorageClass, "findSkinOfPlayer");
                        if (findSkinOfPlayer == null) {
                            findSkinOfPlayer = findOptionalLookupMethod(playerStorageClass, "getSkinOfPlayer");
                        }
                        if (findSkinOfPlayer == null) {
                            findSkinOfPlayer = findOptionalLookupMethod(playerStorageClass, "findPlayerSkin");
                        }
                    } catch (Throwable ignored) {
                        findSkinOfPlayer = null;
                    }
                }

                plugin.getLogger().info("SkinsRestorer API bridge enabled for skin head rendering.");
                return new SkinsRestorerApiBridge(
                        api,
                        getSkinStorage,
                        findSkinData,
                        findOrCreate,
                        getProperty,
                        getTextureUrl,
                        getPlayerStorage,
                        findSkinOfPlayer
                );
            } catch (Throwable throwable) {
                plugin.getLogger().warning("SkinsRestorer detected but API bridge could not be initialized: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                return null;
            }
        }

        private Optional<String> lookupTextureUrlForPlayer(String input, UUID inputUuid, boolean createIfMissing) {
            Optional<String> direct = lookupTextureUrl(input, createIfMissing);
            if (direct.isPresent()) {
                return direct;
            }

            if (inputUuid != null) {
                Optional<String> byUuid = lookupTextureUrl(inputUuid.toString(), createIfMissing);
                if (byUuid.isPresent()) {
                    return byUuid;
                }
            }

            Optional<String> assignedSkin = resolveAssignedSkinIdentifier(input, inputUuid);
            if (assignedSkin.isEmpty()) {
                return Optional.empty();
            }

            return lookupTextureUrl(assignedSkin.get(), createIfMissing);
        }

        private Optional<SkinPropertyData> lookupSkinPropertyForPlayer(String input, UUID inputUuid, boolean createIfMissing) {
            Optional<SkinPropertyData> direct = lookupSkinProperty(input, createIfMissing);
            if (direct.isPresent()) {
                return direct;
            }

            if (inputUuid != null) {
                Optional<SkinPropertyData> byUuid = lookupSkinProperty(inputUuid.toString(), createIfMissing);
                if (byUuid.isPresent()) {
                    return byUuid;
                }
            }

            Optional<String> assignedSkin = resolveAssignedSkinIdentifier(input, inputUuid);
            if (assignedSkin.isEmpty()) {
                return Optional.empty();
            }

            return lookupSkinProperty(assignedSkin.get(), createIfMissing);
        }

        private Optional<String> lookupTextureUrl(String input, boolean createIfMissing) {
            if (input == null || input.isBlank()) {
                return Optional.empty();
            }

            Method lookupMethod = createIfMissing ? findOrCreateSkinDataMethod : findSkinDataMethod;
            if (lookupMethod == null) {
                return Optional.empty();
            }

            try {
                Object skinStorage = getSkinStorageMethod.invoke(skinsRestorerApi);
                Object optionalResult = lookupMethod.invoke(skinStorage, input);
                if (!(optionalResult instanceof Optional<?> result) || result.isEmpty()) {
                    return Optional.empty();
                }

                Object inputData = result.get();
                Object skinProperty = getPropertyMethod.invoke(inputData);
                Object urlObject = getSkinTextureUrlMethod.invoke(null, skinProperty);
                if (!(urlObject instanceof String textureUrl) || textureUrl.isBlank()) {
                    return Optional.empty();
                }

                return Optional.of(textureUrl);
            } catch (Throwable ignored) {
                return Optional.empty();
            }
        }

        private Optional<SkinPropertyData> lookupSkinProperty(String input, boolean createIfMissing) {
            if (input == null || input.isBlank()) {
                return Optional.empty();
            }

            Method lookupMethod = createIfMissing ? findOrCreateSkinDataMethod : findSkinDataMethod;
            if (lookupMethod == null) {
                return Optional.empty();
            }

            try {
                Object skinStorage = getSkinStorageMethod.invoke(skinsRestorerApi);
                Object optionalResult = lookupMethod.invoke(skinStorage, input);
                if (!(optionalResult instanceof Optional<?> result) || result.isEmpty()) {
                    return Optional.empty();
                }

                Object inputData = result.get();
                Object skinProperty = getPropertyMethod.invoke(inputData);
                if (skinProperty == null) {
                    return Optional.empty();
                }

                String value = extractSkinPropertyValue(skinProperty);
                if (value == null || value.isBlank()) {
                    return Optional.empty();
                }

                String signature = extractSkinPropertySignature(skinProperty);
                String textureUrl = extractSkinPropertyTextureUrl(skinProperty);
                return Optional.of(new SkinPropertyData(value, signature, textureUrl, input));
            } catch (Throwable ignored) {
                return Optional.empty();
            }
        }

        private String extractSkinPropertyValue(Object skinProperty) {
            return extractString(skinProperty, "getValue", "value");
        }

        private String extractSkinPropertySignature(Object skinProperty) {
            String signature = extractString(skinProperty, "getSignature", "signature");
            return signature == null ? "" : signature;
        }

        private String extractSkinPropertyTextureUrl(Object skinProperty) {
            try {
                Object urlObject = getSkinTextureUrlMethod.invoke(null, skinProperty);
                if (urlObject instanceof String textureUrl && !textureUrl.isBlank()) {
                    return textureUrl;
                }
            } catch (Throwable ignored) {
            }
            return "";
        }

        private String extractString(Object target, String... methodNames) {
            for (String methodName : methodNames) {
                try {
                    Method method = target.getClass().getMethod(methodName);
                    if (method.getParameterCount() != 0) {
                        continue;
                    }
                    Object value = method.invoke(target);
                    if (value instanceof String str && !str.isBlank()) {
                        return str;
                    }
                } catch (Exception ignored) {
                    // try next accessor
                }
            }
            return null;
        }

        private Optional<String> resolveAssignedSkinIdentifier(String input, UUID inputUuid) {
            if (getPlayerStorageMethod == null || findSkinOfPlayerMethod == null) {
                return Optional.empty();
            }

            try {
                Object playerStorage = getPlayerStorageMethod.invoke(skinsRestorerApi);
                if (playerStorage == null) {
                    return Optional.empty();
                }

                Optional<String> byInput = invokePlayerStorageLookup(playerStorage, input);
                if (byInput.isPresent()) {
                    return byInput;
                }

                if (inputUuid != null) {
                    Optional<String> byUuidObject = invokePlayerStorageLookup(playerStorage, inputUuid);
                    if (byUuidObject.isPresent()) {
                        return byUuidObject;
                    }

                    Optional<String> byUuidString = invokePlayerStorageLookup(playerStorage, inputUuid.toString());
                    if (byUuidString.isPresent()) {
                        return byUuidString;
                    }
                }
            } catch (Throwable ignored) {
                return Optional.empty();
            }

            return Optional.empty();
        }

        private Optional<String> invokePlayerStorageLookup(Object playerStorage, Object key) {
            if (key == null) {
                return Optional.empty();
            }

            try {
                Object result = findSkinOfPlayerMethod.invoke(playerStorage, key);
                if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                    return Optional.empty();
                }

                Object payload = optional.get();
                if (payload instanceof String str && !str.isBlank()) {
                    return Optional.of(str);
                }

                String extracted = tryExtractSkinIdentifier(payload);
                if (extracted == null || extracted.isBlank()) {
                    return Optional.empty();
                }

                return Optional.of(extracted);
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            } catch (Throwable ignored) {
                return Optional.empty();
            }
        }

        private static String tryExtractSkinIdentifier(Object payload) {
            if (payload == null) {
                return null;
            }

            String[] candidateMethods = {"getSkinName", "getName", "getIdentifier", "identifier", "name"};
            for (String methodName : candidateMethods) {
                try {
                    Method method = payload.getClass().getMethod(methodName);
                    if (method.getParameterCount() != 0) {
                        continue;
                    }
                    Object value = method.invoke(payload);
                    if (value instanceof String str && !str.isBlank()) {
                        return str;
                    }
                } catch (Exception ignored) {
                    // try next candidate
                }
            }

            return null;
        }

        private static Method findOptionalLookupMethod(Class<?> type, String methodName) {
            for (Method method : type.getMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                    continue;
                }
                if (!Optional.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                method.setAccessible(true);
                return method;
            }
            return null;
        }
    }
}