package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EcseeCommand implements CommandExecutor, TabCompleter {
    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;

    public EcseeCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }
        if (!player.hasPermission("staff.invsee")) {
            player.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(plugin.message("messages.ecsee-usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(plugin.message("messages.staff-target-not-found").replace("%player%", args[0]));
            return true;
        }

        if (!staffManager.canModerate(player, target)) {
            player.sendMessage(plugin.message("messages.staff-cannot-target"));
            return true;
        }

        staffManager.recordEcsee(player, target, "Opened ender chest via /ecsee");
        player.openInventory(target.getEnderChest());
        player.sendMessage(plugin.message("messages.ecsee-opened").replace("%player%", target.getName()));
        return true;
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