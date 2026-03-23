package com.chisellives;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RevivalListener implements Listener {

    private final JavaPlugin plugin;
    private final RevivalTotemManager revivalTotemManager;
    private final Set<UUID> pendingNamePrompts;

    public RevivalListener(JavaPlugin plugin, RevivalTotemManager revivalTotemManager) {
        this.plugin = plugin;
        this.revivalTotemManager = revivalTotemManager;
        this.pendingNamePrompts = ConcurrentHashMap.newKeySet();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRevivalInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (!isHoldingRevivalTotem(player, event.getHand())) {
            return;
        }

        event.setCancelled(true);
        beginNamePrompt(player);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRevivalInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }

        Player reviver = event.getPlayer();
        if (!isHoldingRevivalTotem(reviver, event.getHand())) {
            return;
        }

        event.setCancelled(true);
        revivalTotemManager.tryRevive(reviver, target);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPromptChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!pendingNamePrompts.contains(playerUuid)) {
            return;
        }

        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> handlePromptInput(player, input));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingNamePrompts.remove(event.getPlayer().getUniqueId());
    }

    private void beginNamePrompt(Player player) {
        UUID uuid = player.getUniqueId();
        if (pendingNamePrompts.contains(uuid)) {
            player.sendMessage("Type the Minecraft name in chat, or type 'cancel' to stop.");
            return;
        }

        pendingNamePrompts.add(uuid);
        player.sendMessage("Type the Minecraft name of the player to revive in chat.");
        player.sendMessage("Type 'cancel' to stop.");
    }

    private void handlePromptInput(Player player, String input) {
        UUID uuid = player.getUniqueId();
        if (!pendingNamePrompts.contains(uuid)) {
            return;
        }

        if (input.equalsIgnoreCase("cancel")) {
            pendingNamePrompts.remove(uuid);
            player.sendMessage("Revival cancelled.");
            return;
        }

        if (!isValidMinecraftName(input)) {
            player.sendMessage("Please enter a valid Minecraft name (3-16 letters, numbers, underscore), or type 'cancel'.");
            return;
        }

        pendingNamePrompts.remove(uuid);
        revivalTotemManager.tryReviveByUsername(player, input);
    }

    private boolean isHoldingRevivalTotem(Player player, EquipmentSlot hand) {
        if (hand == null) {
            return false;
        }

        ItemStack held = getHeldItem(player, hand);
        return revivalTotemManager.isRevivalTotem(held);
    }

    private ItemStack getHeldItem(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            return player.getInventory().getItemInOffHand();
        }
        return player.getInventory().getItemInMainHand();
    }

    private boolean isValidMinecraftName(String input) {
        return input != null && input.matches("^[A-Za-z0-9_]{3,16}$");
    }
}
