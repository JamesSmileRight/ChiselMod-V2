package com.beyondminer.kingdoms.managers;

import java.util.*;

/**
 * Manages kingdom chat toggling for players.
 */
public class ChatManager {
    private final Set<UUID> kingdomChatEnabled;

    public ChatManager() {
        this.kingdomChatEnabled = new HashSet<>();
    }

    /**
     * Toggles kingdom chat for a player.
     */
    public void toggleChat(UUID playerUUID) {
        if (kingdomChatEnabled.contains(playerUUID)) {
            kingdomChatEnabled.remove(playerUUID);
        } else {
            kingdomChatEnabled.add(playerUUID);
        }
    }

    /**
     * Checks if a player has kingdom chat enabled.
     */
    public boolean isChatEnabled(UUID playerUUID) {
        return kingdomChatEnabled.contains(playerUUID);
    }

    /**
     * Enables kingdom chat for a player.
     */
    public void enableChat(UUID playerUUID) {
        kingdomChatEnabled.add(playerUUID);
    }

    /**
     * Disables kingdom chat for a player.
     */
    public void disableChat(UUID playerUUID) {
        kingdomChatEnabled.remove(playerUUID);
    }

    /**
     * Gets all players with kingdom chat enabled.
     */
    public Set<UUID> getEnabledPlayers() {
        return new HashSet<>(kingdomChatEnabled);
    }

    /**
     * Clears all players with kingdom chat enabled (e.g., on plugin disable).
     */
    public void clear() {
        kingdomChatEnabled.clear();
    }
}
