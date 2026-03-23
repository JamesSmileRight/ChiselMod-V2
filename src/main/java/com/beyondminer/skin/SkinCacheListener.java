package com.beyondminer.skin;

import com.beyondminer.leaderboards.managers.DatabaseManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SkinCacheListener implements Listener {

    private final JavaPlugin plugin;
    private final SkinHeadService skinHeadService;
    private final DatabaseManager databaseManager;

    public SkinCacheListener(JavaPlugin plugin, SkinHeadService skinHeadService, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.skinHeadService = skinHeadService;
        this.databaseManager = databaseManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        cacheNowAndLater(player, uuid, name);
    }

    private void cacheNowAndLater(Player player, UUID uuid, String name) {
        // Fast path: if profile skin is already available, persist immediately.
        skinHeadService.resolveSkinTextureUrl(player, false)
                .ifPresent(url -> CompletableFuture.runAsync(() -> databaseManager.upsertPlayerSkin(uuid, name, url)));

        String uuidText = uuid.toString();
        CompletableFuture.runAsync(() -> {
            var resolved = skinHeadService.resolveSkinTextureUrl(name, true);
            if (resolved.isEmpty()) {
                resolved = skinHeadService.resolveSkinTextureUrl(uuidText, true);
            }
            resolved.ifPresent(url -> databaseManager.upsertPlayerSkin(uuid, name, url));
        });

        // Some skin plugins apply textures a short time after join; retry once later.
        player.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            skinHeadService.resolveSkinTextureUrl(player, true)
                    .ifPresent(url -> CompletableFuture.runAsync(() -> databaseManager.upsertPlayerSkin(uuid, name, url)));
        }, 40L);
    }
}
