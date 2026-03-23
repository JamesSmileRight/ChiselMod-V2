package com.chisellives;

import com.chiselranks.ui.GuiStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

public final class LivesGUI implements Listener {

    private static final int INVENTORY_SIZE = 27;
    private static final int LIVES_SLOT = 11;
    private static final int STORE_SLOT = 15;

    private final JavaPlugin plugin;
    private final LivesManager livesManager;

    public LivesGUI(JavaPlugin plugin, LivesManager livesManager) {
        this.plugin = plugin;
        this.livesManager = livesManager;
    }

    public void open(Player player) {
        livesManager.getLives(player.getUniqueId(), player.getName())
                .thenAccept(lives -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    LivesHolder holder = new LivesHolder();
                    Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, GuiStyle.title("Chisel Lives"));
                    holder.inventory = inventory;
                    GuiStyle.frame(inventory);
                    inventory.setItem(LIVES_SLOT, createLivesItem(lives));
                    inventory.setItem(STORE_SLOT, createStoreItem());
                    player.openInventory(inventory);
                    GuiStyle.playOpen(player);
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.SEVERE, "[ChiselLives] Failed to open lives menu.", throwable);
                    return null;
                });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!(event.getInventory().getHolder() instanceof LivesHolder)) {
            return;
        }

        event.setCancelled(true);

        if (event.getRawSlot() != STORE_SLOT) {
            return;
        }

        player.closeInventory();
        GuiStyle.playConfirm(player);

        String storeUrl = livesManager.getStoreUrl();
        player.sendMessage(
                Component.text("Click here to buy lives: ")
                        .append(Component.text(storeUrl)
                                .color(NamedTextColor.AQUA)
                                .decorate(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(storeUrl)))
        );
    }

    private ItemStack createLivesItem(int lives) {
        ItemStack itemStack = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = itemStack.getItemMeta();
        meta.displayName(Component.text("Your Lives", NamedTextColor.GOLD));
        meta.lore(List.of(Component.text("You have " + lives + " lives remaining.", NamedTextColor.WHITE)));
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private ItemStack createStoreItem() {
        ItemStack itemStack = new ItemStack(Material.EMERALD);
        ItemMeta meta = itemStack.getItemMeta();
        meta.displayName(Component.text("Buy Lives", NamedTextColor.GREEN));
        meta.lore(List.of(
                Component.text("Click to open the website store", NamedTextColor.GRAY),
                Component.text(livesManager.getStoreUrl(), NamedTextColor.AQUA)
        ));
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private static final class LivesHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
