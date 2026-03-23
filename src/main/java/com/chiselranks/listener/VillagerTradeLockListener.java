package com.chiselranks.listener;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.Set;

public final class VillagerTradeLockListener implements Listener {
    private static final Set<Villager.Profession> LOCKED_PROFESSIONS = Set.of(
            Villager.Profession.ARMORER,
            Villager.Profession.TOOLSMITH
    );

    private final ChiselRanksPlugin plugin;

    public VillagerTradeLockListener(ChiselRanksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        if (!plugin.getConfig().getBoolean("villager-lock.enabled", false)) {
            return;
        }
        if (!LOCKED_PROFESSIONS.contains(villager.getProfession())) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        player.sendMessage(plugin.message("messages.lockvillagers-blocked"));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7F, 1.0F);
    }
}