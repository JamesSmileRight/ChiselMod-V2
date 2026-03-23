package com.chisellives;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.DiscordWebhookService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

public final class RevivalTotemManager {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final LivesManager livesManager;
    private final BanManager banManager;
    private final NamespacedKey revivalTotemKey;

    public RevivalTotemManager(JavaPlugin plugin, DatabaseManager databaseManager, LivesManager livesManager, BanManager banManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.livesManager = livesManager;
        this.banManager = banManager;
        this.revivalTotemKey = new NamespacedKey(plugin, "revival_totem");
    }

    public ItemStack createRevivalTotemItem() {
        ItemStack itemStack = new ItemStack(org.bukkit.Material.TOTEM_OF_UNDYING);
        ItemMeta meta = itemStack.getItemMeta();

        meta.displayName(Component.text("✦ ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("Revival Totem", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" ✦", NamedTextColor.LIGHT_PURPLE)));

        meta.lore(java.util.List.of(
                Component.text("Bring a fallen friend back to life.", NamedTextColor.GRAY),
                Component.text("Right click a banned player to revive them.", NamedTextColor.GRAY)
        ));

        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(revivalTotemKey, PersistentDataType.BYTE, (byte) 1);

        itemStack.setItemMeta(meta);
        return itemStack;
    }

    public boolean isRevivalTotem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != org.bukkit.Material.TOTEM_OF_UNDYING) {
            return false;
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return false;
        }

        Byte marker = meta.getPersistentDataContainer().get(revivalTotemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public void tryRevive(Player reviver, Player target) {
        if (Objects.equals(reviver.getUniqueId(), target.getUniqueId())) {
            reviver.sendMessage("You cannot use a Revival Totem on yourself.");
            return;
        }

        UUID targetUuid = target.getUniqueId();
        databaseManager.reviveBannedPlayer(targetUuid, livesManager.getRevivalLives())
                .thenAccept(result -> runSync(() -> handleRevivalResult(reviver, result, target)))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.SEVERE, "[ChiselLives] Failed to process revival totem usage.", throwable);
                    runSync(() -> {
                        if (reviver.isOnline()) {
                            reviver.sendMessage("Could not process revival right now. Please try again.");
                        }
                    });
                    return null;
                });
    }

    public void tryReviveByUsername(Player reviver, String rawTargetName) {
        if (rawTargetName == null || rawTargetName.isBlank()) {
            reviver.sendMessage("Usage: /lives revive <player>");
            return;
        }

        if (!hasRevivalTotemInHand(reviver)) {
            reviver.sendMessage("Hold a Revival Totem in your hand to revive someone.");
            return;
        }

        String targetName = rawTargetName.trim();
        databaseManager.reviveBannedPlayerByUsername(targetName, livesManager.getRevivalLives())
                .thenAccept(result -> runSync(() -> handleRevivalResult(reviver, result, null)))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.SEVERE, "[ChiselLives] Failed to process username revival.", throwable);
                    runSync(() -> {
                        if (reviver.isOnline()) {
                            reviver.sendMessage("Could not process revival right now. Please try again.");
                        }
                    });
                    return null;
                });
    }

    private void handleRevivalResult(Player reviver, DatabaseManager.ReviveResult result, Player clickedTarget) {
        if (!reviver.isOnline()) {
            return;
        }

        if (result.status() == DatabaseManager.ReviveStatus.NOT_FOUND) {
            reviver.sendMessage("Player does not exist.");
            return;
        }

        if (result.status() == DatabaseManager.ReviveStatus.STILL_ALIVE) {
            reviver.sendMessage("This player is not dead.");
            reviver.sendMessage("They are still alive.");
            return;
        }

        DatabaseManager.PlayerRecord revivedRecord = result.record();
        if (revivedRecord == null) {
            reviver.sendMessage("Could not process revival right now. Please try again.");
            return;
        }

        Player revivedOnline = Bukkit.getPlayer(revivedRecord.uuid());
        String revivedName = resolveDisplayName(revivedRecord, revivedOnline, clickedTarget);

        banManager.unbanPlayer(revivedRecord.uuid(), revivedName);
        consumeOneRevivalTotem(reviver);

        reviver.sendMessage("You revived " + revivedName + " and gave them a second chance.");
        if (revivedOnline != null && revivedOnline.isOnline()) {
            revivedOnline.sendMessage("You have been revived and given " + revivedRecord.lives() + " lives.");
        }

        Bukkit.broadcast(Component.text("[ChiselLives] " + revivedName + " has been revived using a Revival Totem."));
        DiscordWebhookService discord = discord();
        if (discord != null) {
            discord.sendRevival(revivedName, "Revival Totem", reviver.getName() + " used a Revival Totem.");
        }

        spawnRevivalEffects(reviver, revivedOnline);
        showRevivalTitle(revivedName);

        plugin.getLogger().info("[ChiselLives] Player revived");
    }

    private void consumeOneRevivalTotem(Player player) {
        if (consumeFromMainHand(player) || consumeFromOffHand(player)) {
            player.updateInventory();
        }
    }

    private boolean hasRevivalTotemInHand(Player player) {
        return isRevivalTotem(player.getInventory().getItemInMainHand())
                || isRevivalTotem(player.getInventory().getItemInOffHand());
    }

    private boolean consumeFromMainHand(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!isRevivalTotem(main)) {
            return false;
        }

        if (main.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            main.setAmount(main.getAmount() - 1);
            player.getInventory().setItemInMainHand(main);
        }
        return true;
    }

    private boolean consumeFromOffHand(Player player) {
        ItemStack off = player.getInventory().getItemInOffHand();
        if (!isRevivalTotem(off)) {
            return false;
        }

        if (off.getAmount() <= 1) {
            player.getInventory().setItemInOffHand(null);
        } else {
            off.setAmount(off.getAmount() - 1);
            player.getInventory().setItemInOffHand(off);
        }
        return true;
    }

    private void spawnRevivalEffects(Player reviver, Player revived) {
        Player effectTarget = revived != null && revived.isOnline() ? revived : reviver;
        effectTarget.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, effectTarget.getLocation().add(0.0, 1.0, 0.0), 80, 0.5, 1.0, 0.5, 0.05);
        effectTarget.getWorld().playSound(effectTarget.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
    }

    private void showRevivalTitle(String revivedName) {
        Title title = Title.title(
                Component.text(revivedName + " has been revived!", NamedTextColor.GOLD),
                Component.empty(),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(300))
        );

        Bukkit.getOnlinePlayers().forEach(player -> player.showTitle(title));
    }

    private String resolveDisplayName(DatabaseManager.PlayerRecord record, Player onlineFallback, Player clickedFallback) {
        if (record != null && record.username() != null && !record.username().isBlank()) {
            return record.username();
        }

        if (onlineFallback != null) {
            return onlineFallback.getName();
        }

        if (clickedFallback != null) {
            return clickedFallback.getName();
        }

        return record != null ? record.uuid().toString() : "Unknown";
    }

    private void runSync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }

        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private DiscordWebhookService discord() {
        if (plugin instanceof ChiselRanksPlugin chiselRanksPlugin) {
            return chiselRanksPlugin.getDiscordWebhookService();
        }
        return null;
    }
}
