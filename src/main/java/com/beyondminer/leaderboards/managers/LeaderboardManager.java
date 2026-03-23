package com.beyondminer.leaderboards.managers;

import com.beyondminer.leaderboards.models.LeaderboardEntry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class LeaderboardManager {

    private final DatabaseManager databaseManager;
    private final Executor asyncExecutor;

    public LeaderboardManager(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.asyncExecutor = runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    public CompletableFuture<List<LeaderboardEntry>> getTopPlayers() {
        return CompletableFuture.supplyAsync(() -> fetchLeaderboard(buildTopPlayersQuery(), "player_name"), asyncExecutor);
    }

    public CompletableFuture<List<LeaderboardEntry>> getTopKingdoms() {
        return CompletableFuture.supplyAsync(() -> fetchLeaderboard(buildTopKingdomsQuery(), "kingdom_name"), asyncExecutor);
    }

    private List<LeaderboardEntry> fetchLeaderboard(String query, String nameColumn) {
        List<LeaderboardEntry> entries = new ArrayList<>();

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(query);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                String name = resultSet.getString(nameColumn);
                long bounty = resultSet.getLong("bounty");

                if (name == null || name.isBlank()) {
                    continue;
                }

                entries.add(new LeaderboardEntry(name, bounty));
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to fetch leaderboard data.", exception);
        }

        entries.sort(Comparator.comparingLong(LeaderboardEntry::bounty).reversed());
        if (entries.size() > 10) {
            return new ArrayList<>(entries.subList(0, 10));
        }
        return entries;
    }

    private String buildTopPlayersQuery() {
        return "SELECT COALESCE(p.player_name, pb.player_uuid) AS player_name, pb.bounty " +
                "FROM " + databaseManager.getPlayerBountiesTable() + " pb " +
                "LEFT JOIN " + databaseManager.getPlayersTable() + " p ON p.player_uuid = pb.player_uuid " +
                "ORDER BY pb.bounty DESC LIMIT 10";
    }

    private String buildTopKingdomsQuery() {
        return "SELECT k.name AS kingdom_name, " +
                "COALESCE(SUM(pb.bounty), 0) + COALESCE(MAX(kb.bounty), 0) AS bounty " +
                "FROM " + databaseManager.getKingdomsTable() + " k " +
                "LEFT JOIN " + databaseManager.getKingdomMembersTable() + " km ON km.kingdom = k.name " +
                "LEFT JOIN " + databaseManager.getPlayerBountiesTable() + " pb ON pb.player_uuid = km.player_uuid " +
                "LEFT JOIN " + databaseManager.getKingdomBountiesTable() + " kb ON kb.kingdom_name = k.name " +
                "GROUP BY k.name ORDER BY bounty DESC LIMIT 10";
    }
}
