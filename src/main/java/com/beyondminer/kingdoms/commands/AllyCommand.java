package com.beyondminer.kingdoms.commands;

import com.beyondminer.kingdoms.managers.AllyManager;
import com.beyondminer.kingdoms.managers.KingdomManager;
import com.beyondminer.kingdoms.models.Kingdom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AllyCommand implements CommandExecutor, TabCompleter {

    private static final String KINGDOM_PERMISSION = "kingdoms.command";

    private final AllyManager allyManager;
    private final KingdomManager kingdomManager;

    public AllyCommand(AllyManager allyManager, KingdomManager kingdomManager) {
        this.allyManager = allyManager;
        this.kingdomManager = kingdomManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return true;
        }

        if (!player.hasPermission(KINGDOM_PERMISSION)) {
            player.sendMessage(Component.text("You do not have permission to use ally commands.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            showHelp(player);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "request", "add" -> handleRequest(player, args, 1);
            case "accept" -> handleAccept(player, args);
            case "remove", "delete" -> handleRemove(player, args);
            case "list" -> handleList(player);
            case "help" -> showHelp(player);
            default -> {
                if (args.length == 1) {
                    handleRequest(player, args, 0);
                } else {
                    showHelp(player);
                }
            }
        }

        return true;
    }

    private void handleRequest(Player player, String[] args, int kingdomArgIndex) {
        if (args.length <= kingdomArgIndex) {
            player.sendMessage(Component.text("Usage: /ally request <kingdom>", NamedTextColor.RED));
            return;
        }

        String targetKingdomName = args[kingdomArgIndex];
        AllyManager.AllyRequestResult result = allyManager.requestAlliance(player.getUniqueId(), targetKingdomName);

        switch (result) {
            case SUCCESS -> {
                Kingdom requesterKingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());
                Kingdom targetKingdom = kingdomManager.getKingdom(targetKingdomName);

                if (requesterKingdom == null || targetKingdom == null) {
                    player.sendMessage(Component.text("Could not send ally request right now.", NamedTextColor.RED));
                    return;
                }

                player.sendMessage(Component.text("Alliance request sent to ", NamedTextColor.GREEN)
                        .append(Component.text(targetKingdom.getName(), NamedTextColor.AQUA)));

                Player targetLeader = Bukkit.getPlayer(targetKingdom.getLeader());
                if (targetLeader != null) {
                    targetLeader.sendMessage(Component.text("[Ally] ", NamedTextColor.GOLD)
                            .append(Component.text(requesterKingdom.getName(), NamedTextColor.AQUA))
                            .append(Component.text(" has requested an alliance.", NamedTextColor.WHITE))
                            .append(Component.text(" Use /ally accept " + requesterKingdom.getName(), NamedTextColor.YELLOW)));
                }
            }
            case NOT_IN_KINGDOM -> player.sendMessage(Component.text("You are not in a kingdom.", NamedTextColor.RED));
            case NOT_LEADER -> player.sendMessage(Component.text("Only kingdom leaders can request alliances.", NamedTextColor.RED));
            case TARGET_NOT_FOUND -> player.sendMessage(Component.text("That kingdom does not exist.", NamedTextColor.RED));
            case SAME_KINGDOM -> player.sendMessage(Component.text("You cannot ally your own kingdom.", NamedTextColor.RED));
            case ALREADY_ALLIED -> player.sendMessage(Component.text("Your kingdoms are already allied.", NamedTextColor.RED));
            case REQUEST_ALREADY_SENT -> player.sendMessage(Component.text("You already sent an ally request to that kingdom.", NamedTextColor.RED));
            case TARGET_ALREADY_REQUESTED -> player.sendMessage(Component.text("That kingdom already requested your alliance. Use /ally accept "
                    + targetKingdomName, NamedTextColor.YELLOW));
            case REQUESTER_AT_LIMIT -> player.sendMessage(Component.text("Your kingdom already has the maximum of "
                    + AllyManager.MAX_ALLIES + " allies.", NamedTextColor.RED));
            case TARGET_AT_LIMIT -> player.sendMessage(Component.text("That kingdom already has the maximum of "
                    + AllyManager.MAX_ALLIES + " allies.", NamedTextColor.RED));
        }
    }

    private void handleAccept(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /ally accept <kingdom>", NamedTextColor.RED));
            return;
        }

        String requesterKingdom = args[1];
        AllyManager.AllyAcceptResult result = allyManager.acceptAlliance(player.getUniqueId(), requesterKingdom);

        switch (result) {
            case SUCCESS -> {
                Kingdom accepterKingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());
                Kingdom requester = kingdomManager.getKingdom(requesterKingdom);
                if (accepterKingdom == null || requester == null) {
                    player.sendMessage(Component.text("Could not accept ally request right now.", NamedTextColor.RED));
                    return;
                }

                Bukkit.broadcast(Component.text("[Ally] ", NamedTextColor.GOLD)
                        .append(Component.text(accepterKingdom.getName(), NamedTextColor.AQUA))
                        .append(Component.text(" and ", NamedTextColor.WHITE))
                        .append(Component.text(requester.getName(), NamedTextColor.AQUA))
                        .append(Component.text(" are now allies.", NamedTextColor.WHITE)));
            }
            case NOT_IN_KINGDOM -> player.sendMessage(Component.text("You are not in a kingdom.", NamedTextColor.RED));
            case NOT_LEADER -> player.sendMessage(Component.text("Only kingdom leaders can accept alliances.", NamedTextColor.RED));
            case REQUESTER_NOT_FOUND -> player.sendMessage(Component.text("That kingdom does not exist.", NamedTextColor.RED));
            case NO_PENDING_REQUEST -> player.sendMessage(Component.text("No pending ally request from that kingdom.", NamedTextColor.RED));
            case ALREADY_ALLIED -> player.sendMessage(Component.text("Your kingdoms are already allied.", NamedTextColor.RED));
            case REQUESTER_AT_LIMIT -> player.sendMessage(Component.text("That kingdom reached the ally limit.", NamedTextColor.RED));
            case ACCEPTOR_AT_LIMIT -> player.sendMessage(Component.text("Your kingdom reached the ally limit of "
                    + AllyManager.MAX_ALLIES + ".", NamedTextColor.RED));
        }
    }

    private void handleRemove(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /ally remove <kingdom>", NamedTextColor.RED));
            return;
        }

        String targetKingdom = args[1];
        AllyManager.AllyRemoveResult result = allyManager.removeAlliance(player.getUniqueId(), targetKingdom);

        switch (result) {
            case SUCCESS -> {
                Kingdom removerKingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());
                if (removerKingdom == null) {
                    player.sendMessage(Component.text("Alliance removed.", NamedTextColor.GREEN));
                    return;
                }

                Bukkit.broadcast(Component.text("[Ally] ", NamedTextColor.GOLD)
                        .append(Component.text(removerKingdom.getName(), NamedTextColor.AQUA))
                        .append(Component.text(" ended alliance with ", NamedTextColor.WHITE))
                        .append(Component.text(targetKingdom, NamedTextColor.AQUA))
                        .append(Component.text(".", NamedTextColor.WHITE)));
            }
            case NOT_IN_KINGDOM -> player.sendMessage(Component.text("You are not in a kingdom.", NamedTextColor.RED));
            case NOT_LEADER -> player.sendMessage(Component.text("Only kingdom leaders can remove alliances.", NamedTextColor.RED));
            case TARGET_NOT_FOUND -> player.sendMessage(Component.text("That kingdom does not exist.", NamedTextColor.RED));
            case NOT_ALLIED -> player.sendMessage(Component.text("Your kingdom is not allied with that kingdom.", NamedTextColor.RED));
        }
    }

    private void handleList(Player player) {
        Kingdom kingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());
        if (kingdom == null) {
            player.sendMessage(Component.text("You are not in a kingdom.", NamedTextColor.RED));
            return;
        }

        List<String> allies = allyManager.getAlliesForKingdom(kingdom.getName());
        List<String> incoming = allyManager.getIncomingRequests(kingdom.getName());

        player.sendMessage(Component.text("------ Allies ------", NamedTextColor.GOLD));
        if (allies.isEmpty()) {
            player.sendMessage(Component.text("No current allies.", NamedTextColor.GRAY));
        } else {
            for (String ally : allies) {
                player.sendMessage(Component.text("- " + ally, NamedTextColor.AQUA));
            }
        }

        player.sendMessage(Component.text("------ Incoming Requests ------", NamedTextColor.GOLD));
        if (incoming.isEmpty()) {
            player.sendMessage(Component.text("No incoming ally requests.", NamedTextColor.GRAY));
        } else {
            for (String request : incoming) {
                player.sendMessage(Component.text("- " + request + " (accept: /ally accept " + request + ")", NamedTextColor.YELLOW));
            }
        }

        player.sendMessage(Component.text("Ally limit: " + AllyManager.MAX_ALLIES, NamedTextColor.WHITE));
    }

    private void showHelp(Player player) {
        player.sendMessage(Component.text("-------- Ally Commands --------", NamedTextColor.GOLD));
        player.sendMessage(Component.text("/ally <kingdom>", NamedTextColor.AQUA)
                .append(Component.text(" - Request alliance", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/ally request <kingdom>", NamedTextColor.AQUA)
                .append(Component.text(" - Request alliance", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/ally accept <kingdom>", NamedTextColor.AQUA)
                .append(Component.text(" - Accept ally request", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/ally remove <kingdom>", NamedTextColor.AQUA)
                .append(Component.text(" - Remove alliance", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/ally list", NamedTextColor.AQUA)
                .append(Component.text(" - View allies and requests", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("Ally limit per kingdom: " + AllyManager.MAX_ALLIES, NamedTextColor.WHITE));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("request");
            options.add("accept");
            options.add("remove");
            options.add("list");
            options.add("help");
            options.addAll(kingdomManager.getAllKingdomNames());
            return filter(options, args[0]);
        }

        if (args.length == 2) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);
            if (subcommand.equals("request") || subcommand.equals("accept") || subcommand.equals("remove")) {
                return filter(new ArrayList<>(kingdomManager.getAllKingdomNames()), args[1]);
            }
        }

        return new ArrayList<>();
    }

    private List<String> filter(List<String> options, String partial) {
        String lowered = partial.toLowerCase(Locale.ROOT);
        List<String> filtered = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                filtered.add(option);
            }
        }
        return filtered;
    }
}
