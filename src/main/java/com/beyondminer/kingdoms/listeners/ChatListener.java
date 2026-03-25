package com.beyondminer.kingdoms.listeners;

import com.beyondminer.kingdoms.managers.ChatManager;
import com.beyondminer.kingdoms.managers.KingdomManager;
import com.beyondminer.kingdoms.models.Kingdom;
import com.beyondminer.kingdoms.util.KingdomColorUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Handles chat events and kingdom chat functionality.
 */
public class ChatListener implements Listener {
    private final KingdomManager kingdomManager;
    private final ChatManager chatManager;

    public ChatListener(KingdomManager kingdomManager, ChatManager chatManager) {
        this.kingdomManager = kingdomManager;
        this.chatManager = chatManager;
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Kingdom kingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());

        // Check if player has kingdom chat enabled
        if (!chatManager.isChatEnabled(player.getUniqueId())) {
            event.renderer((source, sourceDisplayName, message, viewer) -> formatMessage(kingdom, sourceDisplayName, message));
            return;
        }

        if (kingdom == null) {
            chatManager.disableChat(player.getUniqueId());
            return;
        }

        event.renderer((source, sourceDisplayName, message, viewer) -> formatMessage(kingdom, sourceDisplayName, message));
        event.viewers().removeIf(audience ->
            !(audience instanceof Player target) || !kingdom.isMember(target.getUniqueId()));
    }

    private Component formatMessage(Kingdom kingdom, Component sourceDisplayName, Component message) {
        Component formatted = Component.empty();

        if (kingdom != null) {
            formatted = formatted
                    .append(Component.text("[", NamedTextColor.GRAY))
                    .append(Component.text(kingdom.getName(), KingdomColorUtil.toTextColor(kingdom.getColor())))
                    .append(Component.text("] ", NamedTextColor.GRAY));
        }

        // Add multi-prefix stacking logic
        String staffRole = chatManager.getStaffRole(player.getUniqueId());
        String rank = chatManager.getRank(player.getUniqueId());

        if (staffRole != null) {
            formatted = formatted.append(Component.text("[" + staffRole + "] ", NamedTextColor.RED));
        }

        if (rank != null) {
            formatted = formatted.append(Component.text("[" + rank + "] ", NamedTextColor.GOLD));
        }

        return formatted
                .append(sourceDisplayName)
                .append(Component.text(": ", NamedTextColor.WHITE))
                .append(message);
    }
}
