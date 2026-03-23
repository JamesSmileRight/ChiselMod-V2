package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ModerationCommand implements CommandExecutor, TabCompleter {
    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;

    public ModerationCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player actor)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }

        String commandName = command.getName().toLowerCase();
        if (args.length == 0) {
            sender.sendMessage(plugin.message("messages." + commandName + "-usage"));
            return true;
        }

        String targetName = args[0];
        String reason = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "No reason provided.";

        if (staffManager.isOnCooldown(actor, commandName)) {
            actor.sendMessage(plugin.message("messages.staff-cooldown"));
            return true;
        }

        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        if (onlineTarget != null && !staffManager.canModerate(actor, onlineTarget)) {
            actor.sendMessage(plugin.message("messages.staff-cannot-target"));
            return true;
        }

        switch (commandName) {
            case "kick" -> {
                if (!actor.hasPermission("staff.kick")) {
                    actor.sendMessage(plugin.message("messages.staff-no-access"));
                    return true;
                }
                if (onlineTarget == null) {
                    actor.sendMessage(plugin.message("messages.staff-target-not-found").replace("%player%", targetName));
                    return true;
                }
                onlineTarget.kickPlayer(reason);
                staffManager.recordPunishment(actor, onlineTarget.getName(), "Kick", reason);
                actor.sendMessage(plugin.message("messages.kick-success").replace("%player%", onlineTarget.getName()));
                return true;
            }
            case "ban" -> {
                if (!actor.hasPermission("staff.ban")) {
                    actor.sendMessage(plugin.message("messages.staff-no-access"));
                    return true;
                }
                Bukkit.getBanList(BanList.Type.NAME).addBan(targetName, reason, null, actor.getName());
                if (onlineTarget != null) {
                    onlineTarget.kickPlayer(reason);
                }
                staffManager.recordPunishment(actor, targetName, "Ban", reason);
                actor.sendMessage(plugin.message("messages.ban-success").replace("%player%", targetName));
                return true;
            }
            case "unban" -> {
                if (!actor.hasPermission("staff.unban")) {
                    actor.sendMessage(plugin.message("messages.staff-no-access"));
                    return true;
                }
                Bukkit.getBanList(BanList.Type.NAME).pardon(targetName);
                staffManager.recordPunishment(actor, targetName, "Unban", reason);
                actor.sendMessage(plugin.message("messages.unban-success").replace("%player%", targetName));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
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
}