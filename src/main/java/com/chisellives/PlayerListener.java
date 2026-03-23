package com.chisellives;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerListener implements Listener {

    private final LivesManager livesManager;

    public PlayerListener(LivesManager livesManager) {
        this.livesManager = livesManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        livesManager.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Only lose lives if killed by another player (PvP)
        // Natural deaths (fall, drown, lava, etc.) do not affect lives
        if (event.getEntity().getKiller() != null) {
            livesManager.handlePlayerDeath(event.getEntity());
        }
    }
}
