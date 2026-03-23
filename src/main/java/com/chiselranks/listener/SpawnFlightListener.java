package com.chiselranks.listener;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class SpawnFlightListener implements Listener {
    private final ChiselRanksPlugin plugin;

    private final Set<UUID> grantedByRegion = new HashSet<>();

    private boolean enabled;
    private boolean requireNetherite;
    private String worldName;
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    public SpawnFlightListener(ChiselRanksPlugin plugin) {
        this.plugin = plugin;
        reloadRegion();
    }

    public void reloadRegion() {
        enabled = plugin.getConfig().getBoolean("spawn-flight.enabled", false);
        requireNetherite = plugin.getConfig().getBoolean("spawn-flight.require-netherite", false);
        worldName = plugin.getConfig().getString("spawn-flight.world", "world");

        minX = plugin.getConfig().getInt("spawn-flight.min.x", 0);
        minY = plugin.getConfig().getInt("spawn-flight.min.y", 0);
        minZ = plugin.getConfig().getInt("spawn-flight.min.z", 0);

        maxX = plugin.getConfig().getInt("spawn-flight.max.x", 0);
        maxY = plugin.getConfig().getInt("spawn-flight.max.y", 255);
        maxZ = plugin.getConfig().getInt("spawn-flight.max.z", 0);
    }

    public void refreshAllOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            updateFlight(player);
        }
    }

    public boolean isRegionEnabled() {
        return enabled && worldName != null && !worldName.isBlank();
    }

    public boolean isInConfiguredRegion(Location location) {
        if (!isRegionEnabled() || location == null || location.getWorld() == null) {
            return false;
        }

        if (!worldName.equals(location.getWorld().getName())) {
            return false;
        }

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        updateFlight(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> updateFlight(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        updateFlight(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        updateFlight(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> updateFlight(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        grantedByRegion.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        if (from.getWorld() == to.getWorld() && from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        updateFlight(event.getPlayer());
    }

    private void updateFlight(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        boolean allowed = isInFlightRegion(player);
        if (allowed) {
            player.setAllowFlight(true);
            grantedByRegion.add(player.getUniqueId());
            return;
        }

        if (grantedByRegion.remove(player.getUniqueId())) {
            player.setAllowFlight(false);
            if (player.isFlying()) {
                player.setFlying(false);
            }
        }
    }

    private boolean isInFlightRegion(Player player) {
        if (player == null || player.getWorld() == null) {
            return false;
        }

        if (player.isOp()) {
            return isInConfiguredRegion(player.getLocation());
        }

        if (!isInConfiguredRegion(player.getLocation())) {
            return false;
        }

        if (plugin.getRankManager() == null) {
            return false;
        }

        if (requireNetherite) {
            return plugin.getRankManager().isNetherite(player);
        }

        return plugin.getRankManager().isGoldOrHigher(player);
    }
}
