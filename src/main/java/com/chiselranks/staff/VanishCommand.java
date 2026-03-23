package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class VanishCommand implements CommandExecutor {
    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;
    private final Map<UUID, GameMode> previousGameModes = new HashMap<>();

    public VanishCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }

        if (!staffManager.canUseVanish(player)) {
            player.sendMessage(plugin.message("messages.staff-vanish-no-access"));
            return true;
        }

        if (args.length > 1) {
            player.sendMessage(plugin.message("messages.staff-vanish-usage"));
            return true;
        }

        boolean vanished;
        if (args.length == 0 || args[0].equalsIgnoreCase("toggle")) {
            vanished = staffManager.toggleVanish(player);
        } else if (args[0].equalsIgnoreCase("on")) {
            vanished = staffManager.toggleVanish(player, true);
        } else if (args[0].equalsIgnoreCase("off")) {
            vanished = staffManager.toggleVanish(player, false);
        } else {
            player.sendMessage(plugin.message("messages.staff-vanish-usage"));
            return true;
        }

        // Handle spectator mode switching
        if (vanished) {
            // Store previous game mode and switch to spectator
            previousGameModes.put(player.getUniqueId(), player.getGameMode());
            player.setGameMode(GameMode.SPECTATOR);
        } else {
            // Restore previous game mode
            GameMode previousMode = previousGameModes.remove(player.getUniqueId());
            if (previousMode != null) {
                player.setGameMode(previousMode);
            } else {
                player.setGameMode(GameMode.SURVIVAL);
            }
        }

        player.sendMessage(plugin.message(vanished ? "messages.staff-vanish-enabled" : "messages.staff-vanish-disabled"));
        return true;
    }
}