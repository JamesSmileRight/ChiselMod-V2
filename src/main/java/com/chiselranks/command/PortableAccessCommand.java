package com.chiselranks.command;

import com.chiselranks.ChiselRanksPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

public final class PortableAccessCommand implements CommandExecutor {
    private final ChiselRanksPlugin plugin;
    private final String permission;
    private final InventoryType inventoryType;
    private final String title;
    private final boolean enderChest;

    public PortableAccessCommand(ChiselRanksPlugin plugin, String permission, InventoryType inventoryType, String title, boolean enderChest) {
        this.plugin = plugin;
        this.permission = permission;
        this.inventoryType = inventoryType;
        this.title = title;
        this.enderChest = enderChest;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }

        if (!player.hasPermission(permission)) {
            player.sendMessage(plugin.message("messages.portable-no-access"));
            return true;
        }

        if (enderChest) {
            player.openInventory(player.getEnderChest());
            return true;
        }

        player.openInventory(Bukkit.createInventory(player, inventoryType, Component.text(title)));
        return true;
    }
}