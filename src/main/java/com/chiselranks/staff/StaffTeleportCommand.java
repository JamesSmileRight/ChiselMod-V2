package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StaffTeleportCommand implements CommandExecutor, TabCompleter {
    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;

    public StaffTeleportCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }
        return handleCommand(player, command.getName().toLowerCase(), args);
    }

    boolean handleCommand(Player player, String commandName, String[] args) {
        if (!staffManager.canUseTeleportCommands(player)) {
            player.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }

        if (commandName.equals("back")) {
            Location back = staffManager.getBackLocation(player);
            if (back == null) {
                player.sendMessage(plugin.message("messages.staff-back-empty"));
                return true;
            }
            staffManager.rememberBackLocation(player, player.getLocation());
            player.teleport(back);
            player.sendMessage(plugin.message("messages.staff-back-success"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(plugin.message(commandName.equals("tp") ? "messages.staff-tp-usage" : "messages.staff-tphere-usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(plugin.message("messages.staff-target-not-found").replace("%player%", args[0]));
            return true;
        }

        if (commandName.equals("tp")) {
            staffManager.rememberBackLocation(player, player.getLocation());
            player.teleport(target.getLocation());
            player.sendMessage(plugin.message("messages.staff-tp-success").replace("%player%", target.getName()));
            return true;
        }

        staffManager.rememberBackLocation(player, player.getLocation());
        target.teleport(player.getLocation());
        player.sendMessage(plugin.message("messages.staff-tphere-success").replace("%player%", target.getName()));
        target.sendMessage(plugin.message("messages.staff-tphere-target").replace("%player%", player.getName()));
        return true;
    }

    List<String> completeTargets(Player player, String[] args) {
        if (!staffManager.canUseTeleportCommands(player)) {
            return List.of();
        }

        if (args.length != 1) {
            return List.of();
        }

        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                matches.add(online.getName());
            }
        }
        return matches;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !staffManager.canUseTeleportCommands(player)) {
            return List.of();
        }

        if (args.length != 1 || command.getName().equalsIgnoreCase("back")) {
            return List.of();
        }

        return completeTargets(player, args);
    }
}