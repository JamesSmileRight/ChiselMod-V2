package com.chiselranks.command;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.listener.SpawnProtectionListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SpawnBreakCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "chiselranks.admin.spawnbreak";

    private final ChiselRanksPlugin plugin;
    private final SpawnProtectionListener spawnProtectionListener;

    public SpawnBreakCommand(ChiselRanksPlugin plugin, SpawnProtectionListener spawnProtectionListener) {
        this.plugin = plugin;
        this.spawnProtectionListener = spawnProtectionListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.message("messages.spawnfly-op-only"));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(plugin.message("messages.spawnbreak-usage"));
            return true;
        }

        String option = args[0].toLowerCase(Locale.ROOT);
        switch (option) {
            case "on" -> {
                plugin.getConfig().set("spawn-flight.block-ops", true);
                plugin.saveConfig();
                sender.sendMessage(plugin.message("messages.spawnbreak-on"));
                return true;
            }
            case "off" -> {
                plugin.getConfig().set("spawn-flight.block-ops", false);
                plugin.saveConfig();
                sender.sendMessage(plugin.message("messages.spawnbreak-off"));
                return true;
            }
            case "info" -> {
                sender.sendMessage(plugin.message("messages.spawnbreak-info")
                        .replace("%state%", spawnProtectionListener.shouldBlockOperators() ? "ON" : "OFF"));
                return true;
            }
            default -> {
                sender.sendMessage(plugin.message("messages.spawnbreak-usage"));
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
        List<String> out = new ArrayList<>();
        for (String option : List.of("on", "off", "info")) {
            if (option.startsWith(input)) {
                out.add(option);
            }
        }
        return out;
    }
}