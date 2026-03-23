package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class AuditLogCommand implements CommandExecutor, Listener {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;

    public AuditLogCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }
        if (!player.hasPermission("staff.auditlog")) {
            player.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }

        AuditHolder holder = new AuditHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Staff Audit Log", NamedTextColor.DARK_RED));
        holder.inventory = inventory;

        int slot = 0;
        for (StaffManager.AuditEntry entry : staffManager.getAuditEntries()) {
            if (slot >= inventory.getSize()) {
                break;
            }

            ItemStack itemStack = new ItemStack(materialFor(entry.type()));
            ItemMeta meta = itemStack.getItemMeta();
            meta.displayName(Component.text("#" + entry.id() + " " + entry.type().displayName(), NamedTextColor.WHITE));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Actor: " + entry.actor(), NamedTextColor.GRAY));
            if (entry.target() != null && !entry.target().isBlank()) {
                lore.add(Component.text("Target: " + entry.target(), NamedTextColor.GRAY));
            }
            lore.add(Component.text("Time: " + TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt())), NamedTextColor.DARK_GRAY));
            lore.add(Component.text(" ", NamedTextColor.BLACK));
            lore.add(Component.text(entry.details(), NamedTextColor.YELLOW));
            meta.lore(lore);
            itemStack.setItemMeta(meta);
            inventory.setItem(slot++, itemStack);
        }

        if (slot == 0) {
            ItemStack itemStack = new ItemStack(Material.BARRIER);
            ItemMeta meta = itemStack.getItemMeta();
            meta.displayName(Component.text("No audit entries yet", NamedTextColor.GRAY));
            itemStack.setItemMeta(meta);
            inventory.setItem(22, itemStack);
        }

        player.openInventory(inventory);
        return true;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof AuditHolder) {
            event.setCancelled(true);
        }
    }

    private Material materialFor(StaffManager.AuditType type) {
        return switch (type) {
            case REPORT -> Material.PAPER;
            case NOTE -> Material.WRITABLE_BOOK;
            case FREEZE -> Material.PACKED_ICE;
            case INVSEE -> Material.CHEST;
            case ECSEE -> Material.ENDER_CHEST;
            case VANISH -> Material.GLASS;
            case ANNOUNCEMENT -> Material.BELL;
            case PUNISHMENT -> Material.IRON_SWORD;
            case STAFF_MODE -> Material.COMPASS;
        };
    }

    private static final class AuditHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}