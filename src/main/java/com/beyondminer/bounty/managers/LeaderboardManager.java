package com.beyondminer.bounty.managers;

import com.beyondminer.bounty.database.DatabaseManager;
import org.bukkit.Bukkit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LeaderboardManager {
    private final KingdomIntegrationManager kingdomIntegration;
    private DatabaseManager database;
    private static final int TOP_LIMIT = 10;

    public LeaderboardManager(DatabaseManager database, KingdomIntegrationManager kingdomIntegration) {
        this.database = database;
        this.kingdomIntegration = kingdomIntegration;
    }

    public List<Map.Entry<String, Integer>> getTopPlayerBounties() {
        List<Map.Entry<String, Integer>> topBounties = new ArrayList<>();

        try {
            if (database.getConnection() == null) {
                return topBounties;
            }

            String query = "SELECT player_uuid, bounty FROM " + database.getPlayerBountiesTable() + " ORDER BY bounty DESC LIMIT ?";
            try (var stmt = database.getConnection().prepareStatement(query)) {
                stmt.setInt(1, TOP_LIMIT);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String playerUuid = rs.getString("player_uuid");
                        String playerName = getPlayerNameFromUUID(playerUuid);
                        int bounty = rs.getInt("bounty");

                        if (playerName != null) {
                            topBounties.add(new java.util.AbstractMap.SimpleEntry<>(playerName, bounty));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return topBounties;
    }

    public List<Map.Entry<String, Integer>> getTopKingdomBounties() {
        if (kingdomIntegration == null || !kingdomIntegration.isPluginLoaded()) {
            return new ArrayList<>();
        }

        return new ArrayList<>(kingdomIntegration.getTopKingdomBounties(TOP_LIMIT));
    }

    private String getPlayerNameFromUUID(String uuidString) {
        try {
            java.util.UUID uuid = java.util.UUID.fromString(uuidString);
            String storedName = database.getPlayerName(uuid);
            if (storedName != null && !storedName.isBlank()) {
                return storedName;
            }

            var player = Bukkit.getPlayer(uuid);
            if (player != null) {
                return player.getName();
            }

            var offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            return offlinePlayer.getName();
        } catch (Exception e) {
            return null;
        }
    }
}
