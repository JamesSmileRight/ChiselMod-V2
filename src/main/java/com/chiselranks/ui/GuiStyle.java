package com.chiselranks.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class GuiStyle {
    private GuiStyle() {
    }

    public static Component title(String text) {
        return Component.text(text, NamedTextColor.GOLD);
    }

    public static ItemStack button(Material material, String title, NamedTextColor color, List<String> lore) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        meta.displayName(Component.text(title, color));
        if (lore != null && !lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                lines.add(Component.text(line, NamedTextColor.GRAY));
            }
            meta.lore(lines);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    public static ItemStack accentPane() {
        ItemStack itemStack = new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        ItemMeta meta = itemStack.getItemMeta();
        meta.displayName(Component.text(" ", NamedTextColor.DARK_AQUA));
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    public static void frame(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == inventory.getSize() / 9 - 1 || column == 0 || column == 8) {
                if (inventory.getItem(slot) == null) {
                    inventory.setItem(slot, accentPane());
                }
            }
        }
    }

    public static void playOpen(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.15f);
    }

    public static void playClick(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.85f, 1.05f);
    }

    public static void playConfirm(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }

    public static void playError(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
    }
}