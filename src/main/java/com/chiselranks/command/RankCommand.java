package com.chiselranks.command;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.RankManager;
import com.chiselranks.rank.Rank;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class RankCommand implements CommandExecutor, TabCompleter {
    private final ChiselRanksPlugin plugin;
    private final RankManager rankManager;

    public RankCommand(ChiselRanksPlugin plugin, RankManager rankManager) {
        this.plugin = plugin;
        this.rankManager = rankManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be run by players.");
            return true;
        }

        if (!player.hasPermission("chiselranks.rank.set")) {
            player.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length < 2) {
            player.sendMessage("§6Usage: /" + label + " <player> <rank|check> [action]");
            player.sendMessage("§6Ranks: gold, diamond, netherite, none");
            player.sendMessage("§6Actions: set (default), grant, clear");
            player.sendMessage("§6Examples:");
            player.sendMessage("§6  /" + label + " PlayerName gold set");
            player.sendMessage("§6  /" + label + " PlayerName diamond grant");
            player.sendMessage("§6  /" + label + " PlayerName none clear");
            player.sendMessage("§6  /" + label + " PlayerName check");
            return true;
        }

        String playerName = args[0];
        String rankInput = args[1].toLowerCase(Locale.ROOT);

        // Special case: check command
        if ("check".equals(rankInput)) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
            Rank currentRank = rankManager.getCachedRank(target.getUniqueId());
            player.sendMessage("§6[Rank] §e" + playerName + " has rank: §f" + 
                    (currentRank == Rank.NONE ? "None" : currentRank.getDisplayName()));
            return true;
        }

        Rank newRank = Rank.fromKey(rankInput).orElse(null);
        if (newRank == null) {
            player.sendMessage("§cUnknown rank: " + rankInput);
            player.sendMessage("§6Available ranks: gold, diamond, netherite, none");
            return true;
        }

        // Determine action (SET is default)
        RankManager.GrantAction action = RankManager.GrantAction.SET;
        if (args.length >= 3) {
            String actionInput = args[2].toLowerCase(Locale.ROOT);
            try {
                action = RankManager.GrantAction.valueOf(actionInput.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                player.sendMessage("§cUnknown action: " + actionInput);
                player.sendMessage("§6Available actions: set, grant, clear");
                return true;
            }
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        Rank result = rankManager.grantRank(target.getUniqueId(), newRank, action);
        
        player.sendMessage("§6[Rank] §eSet " + playerName + "'s rank to §f" + 
                (result == Rank.NONE ? "None" : result.getDisplayName()));

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            // Tab complete player names
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                    matches.add(online.getName());
                }
            }
            return matches;
        }

        if (args.length == 2) {
            // Tab complete ranks
            String input = args[1].toLowerCase(Locale.ROOT);
            List<String> ranks = new ArrayList<>();
            ranks.add("gold");
            ranks.add("diamond");
            ranks.add("netherite");
            ranks.add("none");
            ranks.add("check");
            
            return ranks.stream()
                    .filter(r -> r.startsWith(input))
                    .toList();
        }

        if (args.length == 3) {
            // Tab complete actions
            String input = args[2].toLowerCase(Locale.ROOT);
            List<String> actions = Arrays.asList("set", "grant", "clear");
            return actions.stream()
                    .filter(a -> a.startsWith(input))
                    .toList();
        }

        return new ArrayList<>();
    }
}
