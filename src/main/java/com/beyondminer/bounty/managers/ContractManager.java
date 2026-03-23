package com.beyondminer.bounty.managers;

import com.beyondminer.bounty.database.DatabaseManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ContractManager {
    private DatabaseManager database;

    public static class BountyContract {
        public String targetType;
        public String targetName;
        public String placedBy;
        public int amount;

        public BountyContract(String targetType, String targetName, String placedBy, int amount) {
            this.targetType = targetType;
            this.targetName = targetName;
            this.placedBy = placedBy;
            this.amount = amount;
        }
    }

    public ContractManager(DatabaseManager database) {
        this.database = database;
    }

    public void placeBounty(String targetType, String targetName, String placedBy, int amount) {
        database.addPlacedBounty(targetType, targetName, placedBy, amount);
    }

    public List<BountyContract> getActiveContracts() {
        List<BountyContract> contracts = new ArrayList<>();

        try {
            if (database.getConnection() == null) {
                return contracts;
            }

            String query = "SELECT target_type, target_name, placed_by, amount FROM " + database.getPlacedBountiesTable();
            try (var stmt = database.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    String targetType = rs.getString("target_type");
                    String targetName = rs.getString("target_name");
                    String placedBy = rs.getString("placed_by");
                    int amount = rs.getInt("amount");

                    contracts.add(new BountyContract(targetType, targetName, placedBy, amount));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return contracts;
    }

    public Map<String, Integer> getContractSummary() {
        Map<String, Integer> summary = new LinkedHashMap<>();

        try {
            if (database.getConnection() == null) {
                return summary;
            }

            String playerQuery = "SELECT pb.player_uuid, pb.bounty, p.player_name FROM " + database.getPlayerBountiesTable()
                    + " pb LEFT JOIN " + database.getPlayersTable() + " p ON p.player_uuid = pb.player_uuid"
                    + " WHERE pb.bounty > 0 ORDER BY pb.bounty DESC";
            try (var stmt = database.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(playerQuery)) {
                while (rs.next()) {
                    String name = rs.getString("player_name");
                    if (name == null || name.isBlank()) {
                        name = rs.getString("player_uuid");
                    }
                    summary.put("Player: " + name, rs.getInt("bounty"));
                }
            }

            String kingdomQuery = "SELECT kingdom_name, bounty FROM " + database.getKingdomBountiesTable()
                    + " WHERE bounty > 0 ORDER BY bounty DESC";
            try (var stmt = database.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(kingdomQuery)) {
                while (rs.next()) {
                    summary.put("Kingdom: " + rs.getString("kingdom_name"), rs.getInt("bounty"));
                }
            }
        } catch (SQLException exception) {
            exception.printStackTrace();
        }

        return summary;
    }
}
