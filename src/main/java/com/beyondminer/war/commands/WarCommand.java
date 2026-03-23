package com.beyondminer.war.commands;

import com.beyondminer.kingdoms.managers.KingdomManager;
import com.beyondminer.kingdoms.managers.AllyManager;
import com.beyondminer.kingdoms.models.Kingdom;
import com.beyondminer.war.managers.WarManager;
import com.beyondminer.war.models.War;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class WarCommand implements CommandExecutor, TabCompleter {

    private static final String WAR_PERMISSION = "war.command";

    private final WarManager warManager;
    private final KingdomManager kingdomManager;
    private final AllyManager allyManager;

    public WarCommand(WarManager warManager, KingdomManager kingdomManager) {
        this(warManager, kingdomManager, null);
    }

    public WarCommand(WarManager warManager, KingdomManager kingdomManager, AllyManager allyManager) {
        this.warManager = warManager;
        this.kingdomManager = kingdomManager;
        this.allyManager = allyManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (!player.hasPermission(WAR_PERMISSION)) {
            player.sendMessage("§cYou do not have permission to use war commands.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "declare" -> handleDeclare(player, args);
            case "accept"  -> handleAccept(player, args);
            case "end"     -> handleEnd(player);
            case "list"    -> handleList(player);
            default        -> sendHelp(player);
        }
        return true;
    }

    private void handleDeclare(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /war declare <kingdom>");
            return;
        }

        Kingdom myKingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());
        if (myKingdom == null) {
            player.sendMessage("§c[War] You are not in a kingdom!");
            return;
        }
        if (!myKingdom.getLeader().equals(player.getUniqueId())) {
            player.sendMessage("§c[War] Only kingdom leaders can declare war.");
            return;
        }

        String targetName = args[1];
        if (!kingdomManager.kingdomExists(targetName)) {
            player.sendMessage("§c[War] Kingdom '" + targetName + "' does not exist.");
            return;
        }
        if (myKingdom.getName().equalsIgnoreCase(targetName)) {
            player.sendMessage("§c[War] You cannot declare war on your own kingdom.");
            return;
        }

        if (allyManager != null && allyManager.areAllied(myKingdom.getName(), targetName)) {
            player.sendMessage("§c[War] You cannot declare war on an allied kingdom. Remove alliance first with /ally remove "
                    + targetName + ".");
            return;
        }

        boolean success = warManager.declareWar(player.getUniqueId(), targetName);
        if (!success) {
            player.sendMessage("§c[War] Could not declare war. A war may already be pending or active.");
            return;
        }

        Kingdom target = kingdomManager.getKingdom(targetName);
        player.sendMessage("§e[War] §fWar declared on kingdom §c" + target.getName()
                + "§f! Their leader must accept with §e/war accept " + myKingdom.getName());
        Player targetLeader = Bukkit.getPlayer(target.getLeader());
        if (targetLeader != null) {
            targetLeader.sendMessage("§c[War] §fKingdom §e" + myKingdom.getName()
                    + " §fhas declared war on your kingdom! Use §c/war accept "
                    + myKingdom.getName() + " §fto accept.");
        }
    }

    private void handleAccept(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /war accept <kingdom>");
            return;
        }

        Kingdom myKingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());
        if (myKingdom == null) {
            player.sendMessage("§c[War] You are not in a kingdom!");
            return;
        }
        if (!myKingdom.getLeader().equals(player.getUniqueId())) {
            player.sendMessage("§c[War] Only kingdom leaders can accept war declarations.");
            return;
        }

        String attackerName = args[1];
        boolean success = warManager.acceptWar(player.getUniqueId(), attackerName);
        if (!success) {
            player.sendMessage("§c[War] No pending war declaration from kingdom '" + attackerName + "'.");
            return;
        }

        Kingdom attacker = kingdomManager.getKingdom(attackerName);
        player.sendMessage("§c[War] §fWar with §e" + attacker.getName()
                + " §fis now §cACTIVE§f! Kills grant §e+50% §fbounty bonus!");
        Player attackerLeader = Bukkit.getPlayer(attacker.getLeader());
        if (attackerLeader != null) {
            attackerLeader.sendMessage("§c[War] §fKingdom §e" + myKingdom.getName()
                    + " §faccepted the war! War is now §cACTIVE§f!");
        }
    }

    private void handleEnd(Player player) {
        WarManager.WarEndResult result = warManager.endWarWithResult(player.getUniqueId());
        switch (result.status()) {
            case NOT_IN_KINGDOM -> {
                player.sendMessage("§c[War] You are not in a kingdom!");
                return;
            }
            case NOT_LEADER -> {
                player.sendMessage("§c[War] Only kingdom leaders can end wars.");
                return;
            }
            case NOT_AT_WAR -> {
                player.sendMessage("§c[War] Your kingdom is not currently at war.");
                return;
            }
            case SUCCESS -> {
                if (!result.hasWinner()) {
                    player.sendMessage("§e[War] §fWar ended in a draw. No victory rewards were granted.");
                    return;
                }

                String winner = result.winnerKingdom();
                String loser = winner != null && winner.equalsIgnoreCase(result.endedKingdom())
                        ? result.opponentKingdom()
                        : result.endedKingdom();

                Bukkit.broadcast(Component.text(
                        "§6[War] §e" + winner + " §fwon the war against §c" + loser
                                + "§f! Each winner received §e" + result.memberReward()
                                + " §fbounty points and §e" + result.kingdomVictoryReward()
                                + " §fkingdom bounty was added as victory reward."
                ));

                Kingdom winnerKingdom = kingdomManager.getKingdom(winner);
                if (winnerKingdom != null) {
                    for (java.util.UUID memberUuid : winnerKingdom.getMembers()) {
                        Player member = Bukkit.getPlayer(memberUuid);
                        if (member != null) {
                            member.sendMessage("§6[War] §fVictory reward: §e+" + result.memberReward()
                                    + " §fbounty points.");
                        }
                    }
                }
            }
        }
    }

    private void handleList(Player player) {
        List<War> wars = warManager.getActiveWars();
        player.sendMessage("§c§l========== ACTIVE WARS ==========");
        if (wars.isEmpty()) {
            player.sendMessage("§fNo active wars.");
        } else {
            for (War war : wars) {
                player.sendMessage("§e" + war.getKingdomA() + " §cvs §e" + war.getKingdomB());
            }
        }
        player.sendMessage("§c§l==================================");
    }

    private void sendHelp(Player player) {
        player.sendMessage("§c§l========== WAR COMMANDS ==========");
        player.sendMessage("§f/war declare <kingdom> §8- Declare war on a kingdom");
        player.sendMessage("§f/war accept <kingdom> §8- Accept a war declaration");
        player.sendMessage("§f/war end §8- End war, decide winner, and apply rewards");
        player.sendMessage("§f/war list §8- List all active wars");
        player.sendMessage("§c§l===================================");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("declare", "accept", "end", "list");
        }
        if (args.length == 2 &&
                (args[0].equalsIgnoreCase("declare") || args[0].equalsIgnoreCase("accept"))) {
            return new ArrayList<>(kingdomManager.getAllKingdomNames());
        }
        return List.of();
    }
}
