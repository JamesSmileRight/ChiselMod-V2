package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class StaffListener implements Listener {
    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;
    private final NameTagManager nameTagManager;

    public StaffListener(ChiselRanksPlugin plugin, StaffManager staffManager, NameTagManager nameTagManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
        this.nameTagManager = nameTagManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (staffManager.isVanished(player)) {
            event.joinMessage(null);
        }
        staffManager.syncViewer(player);
        nameTagManager.updatePlayer(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (staffManager.isVanished(player)) {
            event.quitMessage(null);
        }
        if (staffManager.isInStaffMode(player)) {
            staffManager.disableStaffMode(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!staffManager.isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        event.setTo(from);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!staffManager.isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.message("messages.freeze-command-blocked"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (staffManager.isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (staffManager.isInStaffMode(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!staffManager.isStaffChatToggled(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        staffManager.sendStaffChat(event.getPlayer(), net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message()));
    }

}