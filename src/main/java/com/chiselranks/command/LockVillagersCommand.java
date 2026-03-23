package com.chiselranks.command;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LockVillagersCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION = "chiselranks.admin.villagerlock";

    private final ChiselRanksPlugin plugin;

    public LockVillagersCommand(ChiselRanksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(plugin.message("messages.lockvillagers-usage"));
            return true;
        }

        String option = args[0].toLowerCase(Locale.ROOT);
        switch (option) {
            case "on" -> {
                plugin.getConfig().set("villager-lock.enabled", true);
                plugin.saveConfig();
                sender.sendMessage(plugin.message("messages.lockvillagers-on"));
                return true;
            }
            case "off" -> {
                plugin.getConfig().set("villager-lock.enabled", false);
                plugin.saveConfig();
                sender.sendMessage(plugin.message("messages.lockvillagers-off"));
                return true;
            }
            case "status", "info" -> {
                sender.sendMessage(plugin.message("messages.lockvillagers-status")
                        .replace("%state%", plugin.getConfig().getBoolean("villager-lock.enabled", false) ? "ON" : "OFF"));
                return true;
            }
            default -> {
                sender.sendMessage(plugin.message("messages.lockvillagers-usage"));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        for (String option : List.of("on", "off", "status")) {
            if (option.startsWith(input)) {
                options.add(option);
            }
        }
        return options;
    }
}