package com.chiselranks.listener;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.manager.SitManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class SitCleanupListener implements Listener {
    private final ChiselRanksPlugin plugin;
    private final SitManager sitManager;

    public SitCleanupListener(ChiselRanksPlugin plugin, SitManager sitManager) {
        this.plugin = plugin;
        this.sitManager = sitManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (!sitManager.isSitting(player) || !sitManager.isManagedSeat(event.getDismounted())) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> sitManager.standUp(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sitManager.standUp(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (sitManager.isSitting(player)) {
            sitManager.standUp(player);
        }
    }
}
