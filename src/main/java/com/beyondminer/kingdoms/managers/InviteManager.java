package com.beyondminer.kingdoms.managers;

import java.util.*;

/**
 * Manages kingdom invites for players.
 */
public class InviteManager {
    private final Map<UUID, String> invites;

    public InviteManager() {
        this.invites = new HashMap<>();
    }

    /**
     * Invites a player to a kingdom.
     */
    public void invitePlayer(UUID playerUUID, String kingdomName) {
        invites.put(playerUUID, kingdomName);
    }

    /**
     * Gets the kingdom a player is invited to.
     */
    public String getInvite(UUID playerUUID) {
        return invites.get(playerUUID);
    }

    /**
     * Checks if a player has an invite.
     */
    public boolean hasInvite(UUID playerUUID) {
        return invites.containsKey(playerUUID);
    }

    /**
     * Removes an invite.
     */
    public void removeInvite(UUID playerUUID) {
        invites.remove(playerUUID);
    }

    /**
     * Clears all invites for a kingdom (when kingdom is deleted).
     */
    public void clearInvitesForKingdom(String kingdomName) {
        invites.values().removeIf(k -> k.equals(kingdomName));
    }
}
