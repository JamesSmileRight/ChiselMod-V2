package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class StaffCommand implements CommandExecutor, TabCompleter {
    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;
    private final StaffTeleportCommand staffTeleportCommand;

    public StaffCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
        this.staffTeleportCommand = new StaffTeleportCommand(plugin, staffManager);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }

        if (args.length > 0) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);
            if (subcommand.equals("tp")) {
                return staffTeleportCommand.handleCommand(player, "tp", java.util.Arrays.copyOfRange(args, 1, args.length));
            }
            player.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }

        if (!staffManager.canUseStaffMode(player)) {
            player.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }

        boolean enabled = staffManager.toggleStaffMode(player);
        player.sendMessage(plugin.message(enabled ? "messages.staff-mode-enabled" : "messages.staff-mode-disabled"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !staffManager.isStaff(player)) {
            return List.of();
        }

        if (args.length == 1) {
            return "tp".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("tp") : List.of();
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("tp")) {
            return staffTeleportCommand.completeTargets(player, java.util.Arrays.copyOfRange(args, 1, args.length));
        }

        return List.of();
    }
}