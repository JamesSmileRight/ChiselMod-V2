package com.chiselranks.command;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.home.HomeManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class HomeCommand implements CommandExecutor {
    private final ChiselRanksPlugin plugin;
    private final HomeManager homeManager;

    public HomeCommand(ChiselRanksPlugin plugin, HomeManager homeManager) {
        this.plugin = plugin;
        this.homeManager = homeManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }

        String commandName = command.getName().toLowerCase();
        String homeName = args.length > 0 ? args[0] : "home";

        switch (commandName) {
            case "sethome" -> homeManager.setHome(player, homeName)
                    .thenAccept(result -> player.sendMessage(color(result.message())));
            case "delhome" -> homeManager.deleteHome(player, homeName)
                    .thenAccept(result -> player.sendMessage(color(result.message())));
            case "home" -> player.sendMessage(color(homeManager.teleportHome(player, homeName).message()));
            default -> {
                return false;
            }
        }

        return true;
    }

    private String color(String message) {
        return message.replace('&', '§');
    }
}