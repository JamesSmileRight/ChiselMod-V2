package com.beyondminer.leaderboards.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

public final class HelpCommand implements CommandExecutor, TabCompleter {

    private static final String HELP_PERMISSION = "kingdomsbounty.help";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(HELP_PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        sendPlayerHelp(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }

    public static void sendHelp(CommandSender sender) {
        sendPlayerHelp(sender);
    }

    public static void sendPlayerHelp(CommandSender sender) {
        boolean canUseKingdom = sender.hasPermission("kingdoms.command");
        boolean canUseBounty = sender.hasPermission("bounty.command");
        boolean canUseWar = sender.hasPermission("war.command");
        boolean canUseLives = sender.hasPermission("chisellives.use");

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("===== KingdomsBounty Player Commands =====", NamedTextColor.AQUA, TextDecoration.BOLD));
        sendLine(sender, "/chiselhelp", "Show this player command list");

        if (canUseKingdom) {
            sendSection(sender, "Kingdom");
            sendLine(sender, "/kingdom create <name> [color]", "Create a kingdom");
            sendLine(sender, "/kingdom invite <player>", "Invite a player");
            sendLine(sender, "/kingdom join [kingdom]", "Join a pending invite");
            sendLine(sender, "/kingdom leave", "Leave your kingdom");
            sendLine(sender, "/kingdom setcapital", "Set your kingdom capital if you are the creator");
            sendLine(sender, "/kingdom capital", "Teleport to your kingdom capital after a 5 second warmup");
            sendLine(sender, "/kingdom chat", "Toggle kingdom chat");
            sendLine(sender, "/kc", "Shortcut for kingdom chat");
            sendLine(sender, "/ally <request|accept|remove|list>", "Manage kingdom alliances (max 2 allies)");
        }

        if (canUseBounty) {
            sendSection(sender, "Bounty");
            sendLine(sender, "/bounty place player <name>", "Place bounty on a player");
            sendLine(sender, "/bounty place kingdom <name>", "Place bounty on a kingdom");
            sendLine(sender, "/bounty check [player|kingdom] <name>", "Check bounty values");
            sendLine(sender, "/bounty top <players|kingdoms>", "Show top bounties");
            sendLine(sender, "/bounty contracts", "View active bounties");
            sendLine(sender, "/bounty hit request <player>", "Post a public hit request");
            sendLine(sender, "/bounty hit accept <player>", "Accept a hit request");
            sendLine(sender, "/bounty hit list", "View open hit requests");
        }

        if (canUseWar) {
            sendSection(sender, "War");
            sendLine(sender, "/war <declare|accept|end|list>", "Manage kingdom wars");
        }

        if (canUseLives) {
            sendSection(sender, "Lives");
            sendLine(sender, "/lives", "Open the lives menu");
            sendLine(sender, "/lives revive <player>", "Revive a banned player with a Revival Totem");
        }

        sendSection(sender, "Safety");
        sendLine(sender, "/report <player> <reason> [details]", "Preset reasons: hacks, killaura, fly, xray, dupe, nbt, griefing, abuse, chat, bug, other");
        sendLine(sender, "/rankhelp", "Show rank perk and utility commands");
        sendLine(sender, "/spawnhelp", "Show spawn and spawn-admin commands");

        sendLine(sender, "/minecraft:help", "Show the full server command list");
        sender.sendMessage(Component.text("Ops can use /help for the full operator command list.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("===========================================", NamedTextColor.AQUA, TextDecoration.BOLD));
    }

    public static void sendOperatorHelp(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("===== KingdomsBounty Operator Help =====", NamedTextColor.GOLD, TextDecoration.BOLD));
        sendLine(sender, "/help", "Show the full operator command list");
        sendLine(sender, "/chiselhelp", "Show the player-focused command list");

        sendSection(sender, "Player Core Commands");
        sendLine(sender, "/kingdom create <name> [color]", "Create a kingdom");
        sendLine(sender, "/kingdom invite <player>", "Invite a player");
        sendLine(sender, "/kingdom join [kingdom]", "Join a pending invite");
        sendLine(sender, "/kingdom leave", "Leave your kingdom");
        sendLine(sender, "/kingdom setcapital", "Set your kingdom capital if you are the creator");
        sendLine(sender, "/kingdom capital", "Teleport to your kingdom capital after a 5 second warmup");
        sendLine(sender, "/kingdom chat", "Toggle kingdom chat");
        sendLine(sender, "/kc", "Shortcut for kingdom chat");
        sendLine(sender, "/ally <request|accept|remove|list>", "Manage kingdom alliances");
        sendLine(sender, "/bounty <place|check|top|contracts|hit|tags>", "Bounty and hit request commands");
        sendLine(sender, "/war <declare|accept|end|list>", "Kingdom war commands");
        sendLine(sender, "/lives", "Open lives menu");
        sendLine(sender, "/lives revive <player>", "Revive with a Revival Totem");
        sendLine(sender, "/report <player> <reason> [details]", "Preset reasons include hacks, killaura, fly, xray, dupe, nbt, and other");

        sendSection(sender, "Rank and Store Commands");
        sendLine(sender, "/sit", "Gold rank perk");
        sendLine(sender, "/craft", "Portable crafting table");
        sendLine(sender, "/enderchest", "Portable ender chest");
        sendLine(sender, "/anvil", "Portable anvil");
        sendLine(sender, "/cartographytable", "Portable cartography table");
        sendLine(sender, "/stonecutter", "Portable stonecutter");
        sendLine(sender, "/workbench", "Portable workbench");
        sendLine(sender, "/grindstone", "Portable grindstone");
        sendLine(sender, "/loom", "Portable loom");
        sendLine(sender, "/spawnranksnpc <skinname>", "Spawn or move the rank NPC");
        sendLine(sender, "/rankhelp", "Show rank perk and store utility commands");
        sendLine(sender, "/statue create <type> <id> [skin]", "Create a ranks, lives, totem, wiki, or deco statue");
        sendLine(sender, "/statuehelp", "Show the full statue command list");
        sendLine(sender, "/statue remove [id]", "Remove a statue or the one you are looking at");
        sendLine(sender, "/statue movehere [id]", "Move a statue to your location");
        sendLine(sender, "/statue rename <id> <name>", "Change a statue display name");
        sendLine(sender, "/statue skin <id> <premium|offline> <value>", "Use Mojang or SkinRestorer-backed skin data for a statue");

        sendSection(sender, "Admin Commands");
        sendLine(sender, "/staffhelp", "Show the current staff command list");
        sendLine(sender, "/leaderboard refresh", "Refresh both holograms");
        sendLine(sender, "/leaderboard reload", "Reload plugin configuration");
        sendLine(sender, "/leaderboard setplayers", "Set Top Players hologram location");
        sendLine(sender, "/leaderboard setkingdoms", "Set Top Kingdoms hologram location");
        sendLine(sender, "/lives check <player>", "Check a player's lives");
        sendLine(sender, "/lives give <player> <amount>", "Give lives to a player");
        sendLine(sender, "/lives set <player> <amount>", "Set a player's lives");
        sendLine(sender, "/invsee <player>", "Open a player's inventory silently");
        sendLine(sender, "/ecsee <player>", "Open a player's ender chest silently");
        sendLine(sender, "/announce <message>", "Show a global title announcement with sound");
        sendLine(sender, "/staffannounce <message>", "Show a title announcement to staff only");
        sendLine(sender, "/lockvillagers <on|off|status>", "Toggle armourer and toolsmith trade locks");
        sendLine(sender, "/chiselmod reset", "Reset all KingdomsBounty data in MySQL");
        sendLine(sender, "/spawnhelp", "Show the full spawn command list");
        sendLine(sender, "/spawnfly <pos1|pos2|set|clear|info>", "Mark the protected spawn area and spawn-fly region");
        sendLine(sender, "/setspawn", "Set the exact spawn teleport point");
        sendLine(sender, "/spawn", "Teleport to spawn after standing still for 5 seconds");
        sendLine(sender, "/statue list", "List all statue ids and categories");
        sendLine(sender, "/statue info [id]", "Inspect the statue you are looking at or by id");

        sendLine(sender, "/minecraft:help", "Show the full server command list");
        sender.sendMessage(Component.text("========================================", NamedTextColor.GOLD, TextDecoration.BOLD));
    }

    private static void sendSection(CommandSender sender, String title) {
        sender.sendMessage(Component.text("-- " + title + " --", NamedTextColor.YELLOW));
    }

    private static void sendLine(CommandSender sender, String command, String description) {
        sender.sendMessage(Component.text(command, NamedTextColor.GOLD)
                .append(Component.text(" - " + description, NamedTextColor.WHITE)));
    }
}
