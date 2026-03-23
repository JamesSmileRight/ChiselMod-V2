package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class InvseeCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int INVENTORY_SIZE = 54;
    private static final int ARMOR_START_SLOT = 45;
    private static final int OFFHAND_SLOT = 49;

    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;

    public InvseeCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
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
            player.sendMessage(plugin.message("messages.invsee-usage"));
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

        staffManager.recordInvsee(player, target, "Opened inventory via /invsee");
        player.openInventory(createInspectionInventory(player, target));
        player.sendMessage("§aOpened §f" + target.getName() + "§a's inventory.");
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof InvseeHolder holder)) {
            return;
        }

        if (!viewer.getUniqueId().equals(holder.viewerUuid())) {
            event.setCancelled(true);
            return;
        }

        if (event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> syncInventoryToTarget(holder, event.getInventory()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof InvseeHolder holder)) {
            return;
        }

        if (!viewer.getUniqueId().equals(holder.viewerUuid())) {
            event.setCancelled(true);
            return;
        }

        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < event.getInventory().getSize());
        if (!touchesTop) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> syncInventoryToTarget(holder, event.getInventory()));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof InvseeHolder holder)) {
            return;
        }

        if (viewer.getUniqueId().equals(holder.viewerUuid())) {
            syncInventoryToTarget(holder, event.getInventory());
        }
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

    private Inventory createInspectionInventory(Player viewer, Player target) {
        Inventory inventory = Bukkit.createInventory(
                new InvseeHolder(viewer.getUniqueId(), target.getUniqueId()),
                INVENTORY_SIZE,
                Component.text("Invsee: " + target.getName())
        );

        PlayerInventory targetInventory = target.getInventory();
        ItemStack[] storage = targetInventory.getStorageContents();
        for (int slot = 0; slot < storage.length && slot < 36; slot++) {
            inventory.setItem(slot, cloneItem(storage[slot]));
        }

        ItemStack[] armor = targetInventory.getArmorContents();
        for (int index = 0; index < armor.length && ARMOR_START_SLOT + index < INVENTORY_SIZE; index++) {
            inventory.setItem(ARMOR_START_SLOT + index, cloneItem(armor[index]));
        }

        inventory.setItem(OFFHAND_SLOT, cloneItem(targetInventory.getItemInOffHand()));
        return inventory;
    }

    private void syncInventoryToTarget(InvseeHolder holder, Inventory view) {
        Player target = Bukkit.getPlayer(holder.targetUuid());
        if (target == null) {
            return;
        }

        PlayerInventory targetInventory = target.getInventory();
        ItemStack[] storage = new ItemStack[36];
        for (int slot = 0; slot < storage.length; slot++) {
            storage[slot] = cloneItem(view.getItem(slot));
        }
        targetInventory.setStorageContents(storage);

        ItemStack[] armor = new ItemStack[4];
        for (int index = 0; index < armor.length; index++) {
            armor[index] = cloneItem(view.getItem(ARMOR_START_SLOT + index));
        }
        targetInventory.setArmorContents(armor);
        targetInventory.setItemInOffHand(cloneItem(view.getItem(OFFHAND_SLOT)));
        target.updateInventory();
    }

    private ItemStack cloneItem(ItemStack itemStack) {
        return itemStack == null ? null : itemStack.clone();
    }

    private record InvseeHolder(UUID viewerUuid, UUID targetUuid) implements InventoryHolder {
        private InvseeHolder {
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}