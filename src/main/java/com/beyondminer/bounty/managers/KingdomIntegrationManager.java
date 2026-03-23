package com.beyondminer.bounty.managers;

import com.beyondminer.bounty.database.DatabaseManager;
import com.beyondminer.kingdoms.models.Kingdom;
import com.beyondminer.kingdoms.managers.KingdomManager;
import org.bukkit.entity.Player;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class KingdomIntegrationManager {
    private final DatabaseManager database;
    private final BountyManager bountyManager;
    private final KingdomManager kingdomManager;

    public KingdomIntegrationManager(DatabaseManager database, BountyManager bountyManager) {
        this(database, bountyManager, null);
    }

    public KingdomIntegrationManager(DatabaseManager database, BountyManager bountyManager, KingdomManager kingdomManager) {
        this.database = database;
        this.bountyManager = bountyManager;
        this.kingdomManager = kingdomManager;
    }

    public String getPlayerKingdom(UUID playerUuid) {
        if (kingdomManager == null) {
            return null;
        }

        Kingdom kingdom = kingdomManager.getPlayerKingdom(playerUuid);
        return kingdom != null ? kingdom.getName() : null;
    }

    public String getPlayerKingdom(Player player) {
        if (player == null) {
            return null;
        }

        return getPlayerKingdom(player.getUniqueId());
    }

    public boolean kingdomExists(String kingdomName) {
        if (kingdomManager == null || kingdomName == null || kingdomName.isBlank()) {
            return false;
        }

        return kingdomManager.kingdomExists(kingdomName);
    }

    public int getKingdomBounty(String kingdomName) {
        Kingdom kingdom = getKingdom(kingdomName);
        if (kingdom == null) {
            return 0;
        }

        int total = database.getKingdomBounty(kingdom.getName());
        for (UUID memberUuid : kingdom.getMembers()) {
            total += bountyManager.getBountyValue(memberUuid);
        }

        return total;
    }

    public void placeBountyOnKingdom(String kingdomName, int amount) {
        Kingdom kingdom = getKingdom(kingdomName);
        if (kingdom == null) {
            return;
        }

        database.saveKingdomBounty(kingdom.getName(), database.getKingdomBounty(kingdom.getName()) + amount);
    }

    public boolean isPluginLoaded() {
        return kingdomManager != null;
    }

    public List<String> getAllKingdomNames() {
        if (kingdomManager == null) {
            return new ArrayList<>();
        }

        return new ArrayList<>(kingdomManager.getAllKingdomNames());
    }

    public Kingdom getKingdom(String kingdomName) {
        if (kingdomManager == null || kingdomName == null || kingdomName.isBlank()) {
            return null;
        }

        return kingdomManager.getKingdom(kingdomName);
    }

    public List<AbstractMap.SimpleEntry<String, Integer>> getTopKingdomBounties(int limit) {
        List<AbstractMap.SimpleEntry<String, Integer>> totals = new ArrayList<>();
        if (kingdomManager == null) {
            return totals;
        }

        for (Kingdom kingdom : kingdomManager.getAllKingdoms()) {
            totals.add(new AbstractMap.SimpleEntry<>(kingdom.getName(), getKingdomBounty(kingdom.getName())));
        }

        totals.sort(Comparator.comparingInt(AbstractMap.SimpleEntry<String, Integer>::getValue).reversed());
        if (totals.size() > limit) {
            return new ArrayList<>(totals.subList(0, limit));
        }
        return totals;
    }
}
