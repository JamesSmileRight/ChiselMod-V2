package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ReportCommand implements CommandExecutor, TabCompleter {
    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;
    private static final List<String> REASON_KEYS = List.of("hacks", "killaura", "fly", "xray", "dupe", "nbt", "griefing", "abuse", "chat", "bug", "other");

    public ReportCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.message("messages.report-usage"));
            return true;
        }

        String targetName = args[0];
        if (player.getName().equalsIgnoreCase(targetName)) {
            player.sendMessage("§cYou cannot report yourself.");
            return true;
        }

        StaffManager.ReportCategory category = StaffManager.ReportCategory.fromKey(args[1]);
        if (category == StaffManager.ReportCategory.OTHER && !args[1].equalsIgnoreCase("other")) {
            player.sendMessage(plugin.message("messages.report-invalid-reason"));
            return true;
        }

        String details = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim() : "";
        String reason = category.displayName() + (details.isBlank() ? "" : " - " + details);

        StaffManager.ReportPriority priority = staffManager.defaultPriorityForCategory(category);
        StaffManager.ReportEntry entry = staffManager.addReport(player, targetName, reason, category, priority);
        player.sendMessage(plugin.message("messages.report-submitted").replace("%id%", Integer.toString(entry.id())));
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (staffManager.isStaff(online)) {
                online.sendMessage(plugin.message("messages.report-notify")
                        .replace("%reporter%", player.getName())
                        .replace("%target%", targetName)
                        .replace("%reason%", '[' + priority.displayName() + "] [" + category.displayName() + "] " + detailsOrCategory(details, category)));
            }
        }
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
            for (String reason : REASON_KEYS) {
                if (reason.startsWith(input)) {
                    matches.add(reason);
                }
            }
            return matches;
        }
        return List.of();
    }

    private String detailsOrCategory(String details, StaffManager.ReportCategory category) {
        return details.isBlank() ? category.displayName() : details;
    }
}