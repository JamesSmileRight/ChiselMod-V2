package com.chiselranks.command;

import com.chiselranks.ChiselRanksPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SystemHelpCommand implements CommandExecutor {
    public enum Topic {
        STAFF,
        STATUE,
        SPAWN,
        RANK
    }

    private final ChiselRanksPlugin plugin;
    private final Topic topic;

    public SystemHelpCommand(ChiselRanksPlugin plugin, Topic topic) {
        this.plugin = plugin;
        this.topic = topic;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (topic) {
            case STAFF -> sendStaffHelp(sender);
            case STATUE -> sendStatueHelp(sender);
            case SPAWN -> sendSpawnHelp(sender);
            case RANK -> sendRankHelp(sender);
        };
    }

    private boolean sendStaffHelp(CommandSender sender) {
        if (!sender.hasPermission("staff.helper")) {
            sender.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }

        sender.sendMessage(Component.text("===== Staff Commands =====", NamedTextColor.AQUA, TextDecoration.BOLD));
        sendLine(sender, "/staff", "Toggle staff mode");
        sendLine(sender, "/staff tp <player>", "Teleport to a player as staff");
        sendLine(sender, "/vanish [on|off]", "Toggle vanish");
        sendLine(sender, "/sc <message>", "Send staff chat");
        sendLine(sender, "/sctoggle", "Toggle staff chat mode");
        sendLine(sender, "/freeze <player>", "Freeze or unfreeze a player");
        sendLine(sender, "/invsee <player>", "Open a player's inventory");
        sendLine(sender, "/ecsee <player>", "Open a player's ender chest");
        sendLine(sender, "/reports", "Open the reports menu");
        sendLine(sender, "/auditlog", "Open the audit log");
        sendLine(sender, "/kick <player> [reason]", "Kick a player");
        sendLine(sender, "/ban <player> [reason]", "Ban a player");
        sendLine(sender, "/unban <player>", "Unban a player");
        sendLine(sender, "/announce <message>", "Send a global announcement");
        sendLine(sender, "/staffannounce <message>", "Send a staff-only announcement");
        sendLine(sender, "/staffrole <player> <role>", "Set a player's internal staff role");

        if (sender instanceof Player player) {
            if (plugin.getStaffManager().canUseTeleportCommands(player)) {
                sendLine(sender, "/tphere <player>", "Teleport a player to you");
                sendLine(sender, "/back", "Return to your last saved location");
                sendLine(sender, "/staffrtp", "Randomly teleport to an online player");
            }
        } else {
            sendLine(sender, "/staff tp <player>", "Teleport to a player as staff");
            sendLine(sender, "/tphere <player>", "Teleport a player to you");
            sendLine(sender, "/back", "Return to your last saved location");
            sendLine(sender, "/staffrtp", "Randomly teleport to an online player");
        }
        return true;
    }

    private boolean sendStatueHelp(CommandSender sender) {
        if (sender instanceof Player player && !player.isOp() && !player.hasPermission("kingdomsbounty.statue.admin")) {
            sender.sendMessage(Component.text("You do not have permission to use statue commands.", NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text("===== Statue Commands =====", NamedTextColor.GOLD, TextDecoration.BOLD));
        sendLine(sender, "/statue create <ranks|lives|totem|wiki|deco> <id> [skin]", "Create a statue");
        sendLine(sender, "/statue remove [id]", "Remove a statue");
        sendLine(sender, "/statue list", "List all statues");
        sendLine(sender, "/statue movehere [id]", "Move a statue to your location");
        sendLine(sender, "/statue pose <id> <pose>", "Change a statue pose");
        sendLine(sender, "/statue animate <id> <off|idle|merchant|sentinel|seer>", "Change statue animation");
        sendLine(sender, "/statue glow <id> <on|off>", "Toggle glow");
        sendLine(sender, "/statue invisible <id> <on|off>", "Toggle invisible body");
        sendLine(sender, "/statue base <id> <on|off>", "Toggle base plate");
        sendLine(sender, "/statue hands <id> <on|off>", "Toggle hands");
        sendLine(sender, "/statue rename <id> <name>", "Rename a statue");
        sendLine(sender, "/statue subtitle <id> <text|clear>", "Change subtitle");
        sendLine(sender, "/statue skin <id> <premium|offline> <value>", "Use Mojang or SkinRestorer-backed skin data only");
        sendLine(sender, "/statue info [id]", "Show statue details");
        return true;
    }

    private boolean sendSpawnHelp(CommandSender sender) {
        sender.sendMessage(Component.text("===== Spawn Commands =====", NamedTextColor.YELLOW, TextDecoration.BOLD));
        sendLine(sender, "/spawn", "Teleport to spawn after the stand-still delay");
        sendLine(sender, "/spawnfly <pos1|pos2|set|clear|info>", "Manage the protected spawn region");
        sendLine(sender, "/spawnbreak <on|off|info>", "Toggle operator bypass for spawn block protection");
        sendLine(sender, "/setspawn", "Set the exact spawn point");
        return true;
    }

    private boolean sendRankHelp(CommandSender sender) {
        sender.sendMessage(Component.text("===== Rank Commands =====", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        sendLine(sender, "/sit", "Gold rank perk");
        sendLine(sender, "/craft", "Portable crafting table");
        sendLine(sender, "/enderchest", "Portable ender chest");
        sendLine(sender, "/anvil", "Portable anvil");
        sendLine(sender, "/cartographytable", "Portable cartography table");
        sendLine(sender, "/stonecutter", "Portable stonecutter");
        sendLine(sender, "/workbench", "Portable workbench");
        sendLine(sender, "/grindstone", "Portable grindstone");
        sendLine(sender, "/loom", "Portable loom");
        sendLine(sender, "/spawnranksnpc <skinname>", "Spawn the rank shop NPC");
        return true;
    }

    private void sendLine(CommandSender sender, String command, String description) {
        sender.sendMessage(Component.text(command, NamedTextColor.GOLD)
                .append(Component.text(" - " + description, NamedTextColor.WHITE)));
    }
}