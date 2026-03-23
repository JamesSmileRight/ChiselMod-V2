package com.beyondminer.leaderboards.listeners;

import com.beyondminer.leaderboards.commands.HelpCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class HelpListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHelpCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null) {
            return;
        }

        String normalized = raw.trim();
        if (!normalized.equalsIgnoreCase("/help") && !normalized.toLowerCase().startsWith("/help ")) {
            return;
        }

        if (!event.getPlayer().isOp()) {
            return;
        }

        event.setCancelled(true);
        HelpCommand.sendOperatorHelp(event.getPlayer());
    }
}
