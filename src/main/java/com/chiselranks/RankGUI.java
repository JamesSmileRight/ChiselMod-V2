package com.chiselranks;

import com.chiselranks.ui.GuiStyle;
import com.chiselranks.rank.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class RankGUI implements Listener {
    private final ChiselRanksPlugin plugin;

    public RankGUI(ChiselRanksPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Rank currentRank = plugin.getRankManager().getRank(player);
        RankHolder holder = new RankHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, GuiStyle.title("Rank Comparison"));
        holder.setInventory(inventory);
        GuiStyle.frame(inventory);

        inventory.setItem(4, summaryItem(currentRank));
        inventory.setItem(19, rankItem(
                Rank.GOLD,
                Material.GOLD_INGOT,
                List.of(
            perkLine(currentRank, Rank.GOLD, "Use /sit when you need a quick stop anywhere"),
            perkLine(currentRank, Rank.GOLD, "Portable /craft and /enderchest access"),
            perkLine(currentRank, Rank.GOLD, "Colored chat for a cleaner identity in chat"),
            perkLine(currentRank, Rank.GOLD, "Premium quality-of-life starter tier"),
                Component.text("Store page opens on click", NamedTextColor.DARK_GRAY)
            ),
            currentRank));
        inventory.setItem(22, rankItem(
                Rank.DIAMOND,
                Material.DIAMOND,
                List.of(
                        Component.text("Most Popular", NamedTextColor.AQUA),
                perkLine(currentRank, Rank.DIAMOND, "Everything from Gold stays unlocked"),
                perkLine(currentRank, Rank.DIAMOND, "Custom join and leave messages"),
                perkLine(currentRank, Rank.DIAMOND, "Portable anvil, cartography table, and stonecutter"),
                perkLine(currentRank, Rank.DIAMOND, "Three home slots for faster map control"),
                Component.text("Store page opens on click", NamedTextColor.DARK_GRAY)
            ),
            currentRank));
        inventory.setItem(25, rankItem(
                Rank.NETHERITE,
                Material.NETHERITE_INGOT,
                List.of(
                perkLine(currentRank, Rank.NETHERITE, "Everything from Diamond stays unlocked"),
                perkLine(currentRank, Rank.NETHERITE, "Portable workbench, grindstone, and loom"),
                perkLine(currentRank, Rank.NETHERITE, "Custom death message and dark particle trail"),
                perkLine(currentRank, Rank.NETHERITE, "Emoji chat and five home slots"),
                perkLine(currentRank, Rank.NETHERITE, "Spawn flight access when the area requires Netherite"),
                Component.text("Store page opens on click", NamedTextColor.DARK_GRAY)
            ),
            currentRank));

        inventory.setItem(37, comparisonItem("Current Rank", currentRank == Rank.NONE ? "No premium rank yet" : currentRank.getDisplayName(), Material.NAME_TAG, NamedTextColor.WHITE));
        inventory.setItem(40, comparisonItem("Next Upgrade", nextUpgrade(currentRank), Material.EMERALD, NamedTextColor.GREEN));
        inventory.setItem(43, comparisonItem("Store", shortenUrl(storeUrlFor(currentRank == Rank.NETHERITE ? Rank.NETHERITE : nextRank(currentRank))), Material.KNOWLEDGE_BOOK, NamedTextColor.AQUA));
        inventory.setItem(48, comparisonItem("Lives", "Players can buy up to 15 lives total", Material.TOTEM_OF_UNDYING, NamedTextColor.RED));
        inventory.setItem(50, comparisonItem("Revival Totem", "Save a friend by reviving them from the store", Material.ENCHANTED_GOLDEN_APPLE, NamedTextColor.LIGHT_PURPLE));

        player.openInventory(inventory);
        GuiStyle.playOpen(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RankHolder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Rank rank = switch (event.getRawSlot()) {
            case 19 -> Rank.GOLD;
            case 22 -> Rank.DIAMOND;
            case 25 -> Rank.NETHERITE;
            default -> null;
        };

        if (rank == null) {
            return;
        }

        player.closeInventory();
        GuiStyle.playConfirm(player);
        sendStoreLink(player, rank);
    }

    public void sendStoreLink(Player player, Rank rank) {
        String url = storeUrlFor(rank);
        Component message = Component.text("Click here to open the store and purchase this rank", NamedTextColor.AQUA)
            .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Open the " + rank.getDisplayName() + " rank page")));

        player.sendMessage(Component.text("[ChiselRanks] ", NamedTextColor.GOLD)
                .append(Component.text(rank.getDisplayName() + " Rank", colorFor(rank)))
                .append(Component.text(" -> ", NamedTextColor.GRAY))
                .append(message));
    }

    private ItemStack rankItem(Rank rank, Material material, List<Component> lore, Rank currentRank) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        Component title = Component.text(rank.getDisplayName(), colorFor(rank));
        if (currentRank == rank) {
            title = title.append(Component.text("  [CURRENT]", NamedTextColor.GREEN));
        } else if (currentRank.isAtLeast(rank)) {
            title = title.append(Component.text("  [UNLOCKED]", NamedTextColor.GREEN));
        } else {
            title = title.append(Component.text("  [LOCKED]", NamedTextColor.RED));
        }
        meta.displayName(title);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack summaryItem(Rank currentRank) {
        return comparisonItem("Your Rank", currentRank == Rank.NONE ? "Default" : currentRank.getDisplayName(), Material.NETHER_STAR, NamedTextColor.GOLD);
    }

    private ItemStack comparisonItem(String title, String value, Material material, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title, color));
        meta.lore(List.of(Component.text(value, NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private Component perkLine(Rank currentRank, Rank requiredRank, String text) {
        return Component.text((currentRank.isAtLeast(requiredRank) ? "Unlocked: " : "Locked: ") + text,
                currentRank.isAtLeast(requiredRank) ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private String nextUpgrade(Rank currentRank) {
        Rank next = nextRank(currentRank);
        return next == currentRank ? "Highest rank unlocked" : next.getDisplayName();
    }

    private Rank nextRank(Rank currentRank) {
        return switch (currentRank) {
            case NONE -> Rank.GOLD;
            case GOLD -> Rank.DIAMOND;
            case DIAMOND, NETHERITE -> Rank.NETHERITE;
        };
    }

    private String storeUrlFor(Rank rank) {
        String configured = plugin.getConfig().getString("store.rank-pages." + rank.getKey(), "");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return plugin.getStoreUrl();
    }

    private String shortenUrl(String url) {
        if (url == null || url.isBlank()) {
            return plugin.getStoreUrl();
        }
        return url.length() > 36 ? url.substring(0, 33) + "..." : url;
    }

    private NamedTextColor colorFor(Rank rank) {
        return switch (rank) {
            case GOLD -> NamedTextColor.GOLD;
            case DIAMOND -> NamedTextColor.AQUA;
            case NETHERITE -> NamedTextColor.DARK_PURPLE;
            case NONE -> NamedTextColor.WHITE;
        };
    }

    private static final class RankHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}