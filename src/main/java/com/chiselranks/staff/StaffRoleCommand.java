package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.RankManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StaffRoleCommand implements CommandExecutor, TabCompleter {
    private final ChiselRanksPlugin plugin;
    private final RankManager rankManager;

    public StaffRoleCommand(ChiselRanksPlugin plugin, RankManager rankManager) {
        this.plugin = plugin;
        this.rankManager = rankManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }
        if (!player.hasPermission("staff.role.set")) {
            player.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.message("messages.staff-role-usage"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        StaffRole role = StaffRole.fromKey(args[1]).orElse(StaffRole.NONE);

        rankManager.setStaffRole(target.getUniqueId(), role);
        player.sendMessage(plugin.message("messages.staff-role-set")
                .replace("%player%", args[0])
                .replace("%role%", role == StaffRole.NONE ? "none" : role.getDisplayName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
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
            String input = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String role : List.of("owner", "admin", "srmod", "mod", "helper", "none")) {
                if (role.startsWith(input)) {
                    matches.add(role);
                }
            }
            return matches;
        }

        return List.of();
    }
}