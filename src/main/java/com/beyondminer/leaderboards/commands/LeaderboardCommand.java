package com.beyondminer.leaderboards.commands;

import com.beyondminer.leaderboards.BountyLeaderboards;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of("setplayers", "setkingdoms", "remove", "refresh", "reload");
    private static final List<String> REMOVE_TARGETS = List.of("players", "kingdoms", "all");

    private final BountyLeaderboards plugin;

    public LeaderboardCommand(BountyLeaderboards plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("leaderboard.admin")) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        switch (subCommand) {
            case "setplayers" -> {
                if (args.length != 1) {
                    sendUsage(sender);
                    return true;
                }
                setPlayersLocation(sender);
            }
            case "setkingdoms" -> {
                if (args.length != 1) {
                    sendUsage(sender);
                    return true;
                }
                setKingdomsLocation(sender);
            }
            case "remove" -> removeLeaderboard(sender, args);
            case "refresh" -> {
                if (args.length != 1) {
                    sendUsage(sender);
                    return true;
                }
                refreshLeaderboards(sender);
            }
            case "reload" -> {
                if (args.length != 1) {
                    sendUsage(sender);
                    return true;
                }
                reloadPlugin(sender);
            }
            default -> sendUsage(sender);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("leaderboard.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String current = args[0].toLowerCase(Locale.ROOT);
            List<String> results = new ArrayList<>();
            for (String option : SUB_COMMANDS) {
                if (option.startsWith(current)) {
                    results.add(option);
                }
            }
            return results;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            String current = args[1].toLowerCase(Locale.ROOT);
            List<String> results = new ArrayList<>();
            for (String option : REMOVE_TARGETS) {
                if (option.startsWith(current)) {
                    results.add(option);
                }
            }
            return results;
        }

        return Collections.emptyList();
    }

    private void setPlayersLocation(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can set leaderboard locations.", NamedTextColor.RED));
            return;
        }

        plugin.setPlayersLocation(player.getLocation());
        sender.sendMessage(Component.text("Top Players leaderboard location saved.", NamedTextColor.GREEN));
    }

    private void setKingdomsLocation(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can set leaderboard locations.", NamedTextColor.RED));
            return;
        }

        plugin.setKingdomsLocation(player.getLocation());
        sender.sendMessage(Component.text("Top Kingdoms leaderboard location saved.", NamedTextColor.GREEN));
    }

    private void reloadPlugin(CommandSender sender) {
        if (plugin.reloadPlugin()) {
            sender.sendMessage(Component.text("KingdomsBounty reloaded.", NamedTextColor.GREEN));
            return;
        }

        sender.sendMessage(Component.text("Failed to reload plugin. Check console for database errors.", NamedTextColor.RED));
    }

    private void refreshLeaderboards(CommandSender sender) {
        plugin.refreshLeaderboards();
        sender.sendMessage(Component.text("Leaderboard refresh triggered.", NamedTextColor.GREEN));
    }

    private void removeLeaderboard(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sendUsage(sender);
            return;
        }

        String target = args[1].toLowerCase(Locale.ROOT);
        boolean removed;
        Component message;

        switch (target) {
            case "players" -> {
                removed = plugin.clearPlayersLocation();
                message = Component.text(removed
                        ? "Top Players leaderboard location removed."
                        : "Top Players leaderboard location was not set.", removed ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
            }
            case "kingdoms" -> {
                removed = plugin.clearKingdomsLocation();
                message = Component.text(removed
                        ? "Top Kingdoms leaderboard location removed."
                        : "Top Kingdoms leaderboard location was not set.", removed ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
            }
            case "all" -> {
                removed = plugin.clearAllLeaderboardLocations();
                message = Component.text(removed
                        ? "All leaderboard locations removed."
                        : "No leaderboard locations were set.", removed ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
            }
            default -> {
                sendUsage(sender);
                return;
            }
        }

        sender.sendMessage(message);
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /leaderboard <setplayers|setkingdoms|remove <players|kingdoms|all>|refresh|reload>", NamedTextColor.YELLOW));
    }
}
