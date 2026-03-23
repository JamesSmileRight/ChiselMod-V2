package com.beyondminer.kingdoms.listeners;

import com.beyondminer.bounty.database.DatabaseManager;
import com.beyondminer.kingdoms.managers.ChatManager;
import com.beyondminer.kingdoms.managers.KingdomManager;
import com.beyondminer.kingdoms.models.Kingdom;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Handles player join and quit events.
 */
public class JoinListener implements Listener {
    private final ChatManager chatManager;
    private final DatabaseManager bountyDatabaseManager;
    private final KingdomManager kingdomManager;

    public JoinListener(ChatManager chatManager) {
        this(chatManager, null, null);
    }

    public JoinListener(ChatManager chatManager, DatabaseManager bountyDatabaseManager) {
        this(chatManager, bountyDatabaseManager, null);
    }

    public JoinListener(ChatManager chatManager, KingdomManager kingdomManager) {
        this(chatManager, null, kingdomManager);
    }

    public JoinListener(ChatManager chatManager, DatabaseManager bountyDatabaseManager, KingdomManager kingdomManager) {
        this.chatManager = chatManager;
        this.bountyDatabaseManager = bountyDatabaseManager;
        this.kingdomManager = kingdomManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joiningPlayer = event.getPlayer();

        if (bountyDatabaseManager != null) {
            bountyDatabaseManager.savePlayer(joiningPlayer.getUniqueId(), joiningPlayer.getName());
        }

        if (kingdomManager == null) {
            return;
        }

        Kingdom kingdom = kingdomManager.getPlayerKingdom(joiningPlayer.getUniqueId());
        if (kingdom == null) {
            return;
        }

        String message = "§6[Kingdom] §eMember " + joiningPlayer.getName() + " joined the game.";
        for (UUID memberUUID : kingdom.getMembers()) {
            if (memberUUID.equals(joiningPlayer.getUniqueId())) {
                continue;
            }

            Player member = Bukkit.getPlayer(memberUUID);
            if (member != null) {
                member.sendMessage(message);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Disable kingdom chat for the player when they quit
        chatManager.disableChat(event.getPlayer().getUniqueId());
    }
}
