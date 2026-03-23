package com.beyondminer.kingdoms.managers;

import com.beyondminer.kingdoms.database.DatabaseManager;
import com.beyondminer.kingdoms.models.Kingdom;
import org.bukkit.Location;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all kingdom operations and persistence.
 */
public class KingdomManager {
    private final DatabaseManager databaseManager;
    private final Map<String, Kingdom> kingdoms;

    public KingdomManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.kingdoms = new ConcurrentHashMap<>();
        loadKingdoms();
    }

    private void loadKingdoms() {
        for (Kingdom kingdom : databaseManager.loadAllKingdoms().values()) {
            kingdoms.put(normalizeKey(kingdom.getName()), kingdom);
        }
    }

    /**
     * Creates a new kingdom if it doesn't already exist.
     */
    public boolean createKingdom(String name, UUID leader) {
        return createKingdom(name, leader, "gold");
    }

    public boolean createKingdom(String name, UUID leader, String color) {
        String key = normalizeKey(name);
        if (kingdoms.containsKey(key)) {
            return false;
        }

        Kingdom kingdom = new Kingdom(name, leader, color, System.currentTimeMillis());
        kingdoms.put(key, kingdom);
        databaseManager.saveKingdom(kingdom);
        return true;
    }

    /**
     * Deletes a kingdom if it exists.
     */
    public boolean deleteKingdom(String name) {
        Kingdom kingdom = kingdoms.remove(normalizeKey(name));
        if (kingdom == null) {
            return false;
        }

        databaseManager.deleteKingdom(kingdom.getName());
        return true;
    }

    /**
     * Gets a kingdom by name.
     */
    public Kingdom getKingdom(String name) {
        if (name == null) {
            return null;
        }

        return kingdoms.get(normalizeKey(name));
    }

    /**
     * Gets the kingdom a player is in.
     */
    public Kingdom getPlayerKingdom(UUID playerUUID) {
        for (Kingdom kingdom : kingdoms.values()) {
            if (kingdom.isMember(playerUUID)) {
                return kingdom;
            }
        }
        return null;
    }

    /**
     * Adds a player to a kingdom.
     */
    public boolean addPlayerToKingdom(UUID playerUUID, String kingdomName) {
        Kingdom kingdom = getKingdom(kingdomName);
        if (kingdom == null) {
            return false;
        }

        kingdom.addMember(playerUUID);
        databaseManager.saveKingdom(kingdom);
        return true;
    }

    /**
     * Removes a player from their kingdom.
     */
    public boolean removePlayerFromKingdom(UUID playerUUID) {
        Kingdom kingdom = getPlayerKingdom(playerUUID);
        if (kingdom == null) {
            return false;
        }

        kingdom.removeMember(playerUUID);
        databaseManager.saveKingdom(kingdom);

        // If the leader left and no members remain, delete the kingdom
        if (kingdom.getMemberCount() == 0) {
            deleteKingdom(kingdom.getName());
        }

        return true;
    }

    /**
     * Gets all kingdom names.
     */
    public Collection<String> getAllKingdomNames() {
        List<String> names = new ArrayList<>();
        for (Kingdom kingdom : kingdoms.values()) {
            names.add(kingdom.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * Gets all kingdoms.
     */
    public Collection<Kingdom> getAllKingdoms() {
        return new ArrayList<>(kingdoms.values());
    }

    public synchronized void reloadFromDatabase() {
        kingdoms.clear();
        loadKingdoms();
    }

    /**
     * Checks if a kingdom exists.
     */
    public boolean kingdomExists(String name) {
        return getKingdom(name) != null;
    }

    /**
     * Checks if a player is in a kingdom.
     */
    public boolean isPlayerInKingdom(UUID playerUUID) {
        return getPlayerKingdom(playerUUID) != null;
    }

    public boolean setKingdomCapital(String kingdomName, Location capital) {
        Kingdom kingdom = getKingdom(kingdomName);
        if (kingdom == null) {
            return false;
        }

        kingdom.setCapital(capital);
        databaseManager.saveKingdom(kingdom);
        return true;
    }

    private String normalizeKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
