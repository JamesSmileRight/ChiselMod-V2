package com.beyondminer.bounty.database;

import com.beyondminer.assassination.models.AssassinationContract;
import com.beyondminer.bounty.models.PlayerBounty;
import com.beyondminer.war.models.War;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class DatabaseManager {
    private static final int MYSQL_CONNECTION_VALIDATION_TIMEOUT_SECONDS = 2;

    private final JavaPlugin plugin;

    private Connection connection;
    private boolean initialized;
    private boolean hasLoggedConnectionSuccess;
    private boolean mysqlMode;
    private String configuredMode;
    private String jdbcUrl;
    private String username;
    private String password;

    private String playersTable;
    private String playerBountiesTable;
    private String kingdomBountiesTable;
    private String placedBountiesTable;
    private String killCooldownTable;
    private String warsTable;
    private String warKillsTable;
    private String assassinationContractsTable;
    private String hitContractsTable;
    private final Map<String, Long> killCooldownCache = new ConcurrentHashMap<>();

    public DatabaseManager(File dataFolder) {
        this(dataFolder, null);
    }

    public DatabaseManager(File dataFolder, JavaPlugin plugin) {
        this.plugin = plugin;
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        initialize();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getPlayersTable() {
        return playersTable;
    }

    public String getPlayerBountiesTable() {
        return playerBountiesTable;
    }

    public String getKingdomBountiesTable() {
        return kingdomBountiesTable;
    }

    public String getPlacedBountiesTable() {
        return placedBountiesTable;
    }

    public String getHitContractsTable() {
        return hitContractsTable;
    }

    public void initialize() {
        initialized = false;
        hasLoggedConnectionSuccess = false;
        close();
        buildTableNames();
        configureMode();

        if (!openConnection()) {
            return;
        }

        initialized = createTables();
    }

    public void savePlayer(UUID playerUuid, String playerName) {
        Connection activeConnection = getConnection();
        if (activeConnection == null || playerName == null || playerName.isBlank()) {
            return;
        }

        String update = String.format("UPDATE %s SET player_name = ?, last_seen = ? WHERE player_uuid = ?", playersTable);
        String insert = String.format("INSERT INTO %s (player_uuid, player_name, last_seen) VALUES (?, ?, ?)", playersTable);
        long now = System.currentTimeMillis();

        try (PreparedStatement updateStmt = activeConnection.prepareStatement(update)) {
            updateStmt.setString(1, playerName);
            updateStmt.setLong(2, now);
            updateStmt.setString(3, playerUuid.toString());
            int updated = updateStmt.executeUpdate();

            if (updated == 0) {
                try (PreparedStatement insertStmt = activeConnection.prepareStatement(insert)) {
                    insertStmt.setString(1, playerUuid.toString());
                    insertStmt.setString(2, playerName);
                    insertStmt.setLong(3, now);
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            logSevere("Failed to save player identity.", exception);
        }
    }

    public UUID findPlayerUuidByName(String playerName) {
        Connection activeConnection = getConnection();
        if (activeConnection == null || playerName == null || playerName.isBlank()) {
            return null;
        }

        String query = String.format(
                "SELECT player_uuid FROM %s WHERE LOWER(player_name) = LOWER(?) ORDER BY last_seen DESC LIMIT 1",
                playersTable
        );

        try (PreparedStatement stmt = activeConnection.prepareStatement(query)) {
            stmt.setString(1, playerName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString("player_uuid"));
                }
            }
        } catch (SQLException | IllegalArgumentException exception) {
            logSevere("Failed to resolve player UUID from stored player name.", exception);
        }

        return null;
    }

    public String getPlayerName(UUID playerUuid) {
        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            return null;
        }

        String query = String.format("SELECT player_name FROM %s WHERE player_uuid = ?", playersTable);
        try (PreparedStatement stmt = activeConnection.prepareStatement(query)) {
            stmt.setString(1, playerUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("player_name");
                }
            }
        } catch (SQLException exception) {
            logSevere("Failed to resolve player name from stored player data.", exception);
        }

        return null;
    }

    public void savePlayerBounty(UUID playerUuid, int bounty, int kills, int deaths) {
        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            return;
        }

        String update = String.format("UPDATE %s SET bounty = ?, kills = ?, deaths = ? WHERE player_uuid = ?", playerBountiesTable);
        String insert = String.format("INSERT INTO %s (player_uuid, bounty, kills, deaths) VALUES (?, ?, ?, ?)", playerBountiesTable);

        try (PreparedStatement updateStmt = activeConnection.prepareStatement(update)) {
            updateStmt.setInt(1, bounty);
            updateStmt.setInt(2, kills);
            updateStmt.setInt(3, deaths);
            updateStmt.setString(4, playerUuid.toString());
            int updated = updateStmt.executeUpdate();

            if (updated == 0) {
                try (PreparedStatement insertStmt = activeConnection.prepareStatement(insert)) {
                    insertStmt.setString(1, playerUuid.toString());
                    insertStmt.setInt(2, bounty);
                    insertStmt.setInt(3, kills);
                    insertStmt.setInt(4, deaths);
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            logSevere("Failed to save player bounty.", exception);
        }
    }

    public int getPlayerBounty(UUID playerUuid) {
        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            return 0;
        }

        String query = String.format("SELECT bounty FROM %s WHERE player_uuid = ?", playerBountiesTable);
        try (PreparedStatement stmt = activeConnection.prepareStatement(query)) {
            stmt.setString(1, playerUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("bounty");
                }
            }
        } catch (SQLException exception) {
            logSevere("Failed to fetch player bounty.", exception);
        }
        return 0;
    }

    public PlayerBounty loadPlayerBounty(UUID playerUuid) {
        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            return new PlayerBounty(playerUuid, 0, 0, 0);
        }

        String query = String.format("SELECT bounty, kills, deaths FROM %s WHERE player_uuid = ?", playerBountiesTable);
        try (PreparedStatement stmt = activeConnection.prepareStatement(query)) {
            stmt.setString(1, playerUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new PlayerBounty(
                            playerUuid,
                            rs.getInt("bounty"),
                            rs.getInt("kills"),
                            rs.getInt("deaths")
                    );
                }
            }
        } catch (SQLException exception) {
            logSevere("Failed to load full player bounty record.", exception);
        }

        return new PlayerBounty(playerUuid, 0, 0, 0);
    }

    public void saveKingdomBounty(String kingdomName, int bounty) {
        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            return;
        }

        String update = String.format("UPDATE %s SET bounty = ? WHERE kingdom_name = ?", kingdomBountiesTable);
        String insert = String.format("INSERT INTO %s (kingdom_name, bounty) VALUES (?, ?)", kingdomBountiesTable);

        try (PreparedStatement updateStmt = activeConnection.prepareStatement(update)) {
            updateStmt.setInt(1, bounty);
            updateStmt.setString(2, kingdomName);
            int updated = updateStmt.executeUpdate();

            if (updated == 0) {
                try (PreparedStatement insertStmt = activeConnection.prepareStatement(insert)) {
                    insertStmt.setString(1, kingdomName);
                    insertStmt.setInt(2, bounty);
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            logSevere("Failed to save kingdom bounty.", exception);
        }
    }

    public int getKingdomBounty(String kingdomName) {
        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            return 0;
        }

        String query = String.format("SELECT bounty FROM %s WHERE kingdom_name = ?", kingdomBountiesTable);
        try (PreparedStatement stmt = activeConnection.prepareStatement(query)) {
            stmt.setString(1, kingdomName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("bounty");
                }
            }
        } catch (SQLException exception) {
            logSevere("Failed to fetch stored kingdom bounty.", exception);
        }
        return 0;
    }

    public void addPlacedBounty(String targetType, String targetName, String placedBy, int amount) {
        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            return;
        }

        String query = String.format(
                "INSERT INTO %s (target_type, target_name, placed_by, amount) VALUES (?, ?, ?, ?)",
                placedBountiesTable
        );

        try (PreparedStatement stmt = activeConnection.prepareStatement(query)) {
            stmt.setString(1, targetType);
            stmt.setString(2, targetName);
            stmt.setString(3, placedBy);
            stmt.setInt(4, amount);
            stmt.executeUpdate();
        } catch (SQLException exception) {
            logSevere("Failed to add placed bounty contract.", exception);
        }
    }

    public void recordKillCooldown(UUID killerUuid, UUID victimUuid) {
        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            return;
        }

        long now = System.currentTimeMillis();
        killCooldownCache.put(buildCooldownKey(killerUuid, victimUuid), now);

        String update = String.format("UPDATE %s SET timestamp = ? WHERE killer_uuid = ? AND victim_uuid = ?", killCooldownTable);
        String insert = String.format("INSERT INTO %s (killer_uuid, victim_uuid, timestamp) VALUES (?, ?, ?)", killCooldownTable);

        try (PreparedStatement updateStmt = activeConnection.prepareStatement(update)) {
            updateStmt.setLong(1, now);
            updateStmt.setString(2, killerUuid.toString());
            updateStmt.setString(3, victimUuid.toString());
            int updated = updateStmt.executeUpdate();

            if (updated == 0) {
                try (PreparedStatement insertStmt = activeConnection.prepareStatement(insert)) {
                    insertStmt.setString(1, killerUuid.toString());
                    insertStmt.setString(2, victimUuid.toString());
                    insertStmt.setLong(3, now);
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            logSevere("Failed to record kill cooldown.", exception);
        }
    }

    public boolean isKillCooldownActive(UUID killerUuid, UUID victimUuid) {
        long tenMinutesInMs = 10L * 60L * 1000L;
        long now = System.currentTimeMillis();
        String cooldownKey = buildCooldownKey(killerUuid, victimUuid);

        Long cachedTimestamp = killCooldownCache.get(cooldownKey);
        if (cachedTimestamp != null) {
            if ((now - cachedTimestamp) < tenMinutesInMs) {
                return true;
            }
            killCooldownCache.remove(cooldownKey);
        }

        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            return false;
        }

        String query = String.format(
                "SELECT timestamp FROM %s WHERE killer_uuid = ? AND victim_uuid = ?",
                killCooldownTable
        );

        try (PreparedStatement stmt = activeConnection.prepareStatement(query)) {
            stmt.setString(1, killerUuid.toString());
            stmt.setString(2, victimUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long timestamp = rs.getLong("timestamp");
                    if ((now - timestamp) < tenMinutesInMs) {
                        killCooldownCache.put(cooldownKey, timestamp);
                        return true;
                    }
                }
            }
        } catch (SQLException exception) {
            logSevere("Failed to check kill cooldown.", exception);
        }
        return false;
    }

    private String buildCooldownKey(UUID killerUuid, UUID victimUuid) {
        return killerUuid + ":" + victimUuid;
    }
    // -------------------------------------------------------------------------
    // War persistence
    // -------------------------------------------------------------------------

    public boolean hasPendingWarRequest(String fromKingdom, String toKingdom) {
        Connection conn = getConnection();
        if (conn == null) return false;
        String sql = "SELECT 1 FROM " + warsTable + " WHERE kingdom_a = ? AND kingdom_b = ? AND active = 0";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, fromKingdom);
            stmt.setString(2, toKingdom);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logSevere("Failed to check pending war request.", e);
        }
        return false;
    }

    public void insertWarRequest(String kingdomA, String kingdomB, long startTime) {
        Connection conn = getConnection();
        if (conn == null) return;
        String sql = "INSERT INTO " + warsTable + " (kingdom_a, kingdom_b, start_time, active) VALUES (?, ?, ?, 0)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, kingdomA);
            stmt.setString(2, kingdomB);
            stmt.setLong(3, startTime);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logSevere("Failed to insert war request.", e);
        }
    }

    public void activateWar(String kingdomA, String kingdomB) {
        Connection conn = getConnection();
        if (conn == null) return;
        String sql = "UPDATE " + warsTable + " SET active = 1 WHERE kingdom_a = ? AND kingdom_b = ? AND active = 0";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, kingdomA);
            stmt.setString(2, kingdomB);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logSevere("Failed to activate war.", e);
        }
    }

    public boolean endWarForKingdom(String kingdom) {
        Connection conn = getConnection();
        if (conn == null) return false;
        String sql = "DELETE FROM " + warsTable + " WHERE (kingdom_a = ? OR kingdom_b = ?) AND active = 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, kingdom);
            stmt.setString(2, kingdom);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logSevere("Failed to end war for kingdom.", e);
        }
        return false;
    }

    public boolean areAtWar(String kingdom1, String kingdom2) {
        Connection conn = getConnection();
        if (conn == null) return false;
        String sql = "SELECT 1 FROM " + warsTable
                + " WHERE ((kingdom_a = ? AND kingdom_b = ?) OR (kingdom_a = ? AND kingdom_b = ?)) AND active = 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, kingdom1);
            stmt.setString(2, kingdom2);
            stmt.setString(3, kingdom2);
            stmt.setString(4, kingdom1);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logSevere("Failed to check war status.", e);
        }
        return false;
    }

    public List<War> getActiveWars() {
        Connection conn = getConnection();
        List<War> wars = new ArrayList<>();
        if (conn == null) return wars;
        String sql = "SELECT kingdom_a, kingdom_b, start_time FROM " + warsTable + " WHERE active = 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                wars.add(new War(rs.getString("kingdom_a"), rs.getString("kingdom_b"), rs.getLong("start_time"), true));
            }
        } catch (SQLException e) {
            logSevere("Failed to fetch active wars.", e);
        }
        return wars;
    }

    public void insertWarKill(UUID killerUuid, UUID victimUuid, String killerKingdom, String victimKingdom, long timestamp) {
        Connection conn = getConnection();
        if (conn == null) return;
        String sql = "INSERT INTO " + warKillsTable
                + " (killer_uuid, victim_uuid, kingdom_killer, kingdom_victim, timestamp) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, killerUuid.toString());
            stmt.setString(2, victimUuid.toString());
            stmt.setString(3, killerKingdom);
            stmt.setString(4, victimKingdom);
            stmt.setLong(5, timestamp);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logSevere("Failed to record war kill.", e);
        }
    }

    public int countWarKills(String killerKingdom, String victimKingdom, long warStartTime) {
        Connection conn = getConnection();
        if (conn == null) return 0;

        String sql = "SELECT COUNT(*) AS total FROM " + warKillsTable
                + " WHERE kingdom_killer = ? AND kingdom_victim = ? AND timestamp >= ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, killerKingdom);
            stmt.setString(2, victimKingdom);
            stmt.setLong(3, warStartTime);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("total");
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to count war kills.", e);
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Assassination contract persistence
    // -------------------------------------------------------------------------

    public void insertAssassinationContract(UUID targetUuid, String placedBy, int amount, long timestamp) {
        Connection conn = getConnection();
        if (conn == null) return;
        String sql = "INSERT INTO " + assassinationContractsTable
                + " (target_uuid, placed_by, amount, timestamp) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, targetUuid.toString());
            stmt.setString(2, placedBy);
            stmt.setInt(3, amount);
            stmt.setLong(4, timestamp);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logSevere("Failed to insert assassination contract.", e);
        }
    }

    public int getTotalAssassinationValue(UUID targetUuid) {
        Connection conn = getConnection();
        if (conn == null) return 0;
        String sql = "SELECT COALESCE(SUM(amount), 0) AS total FROM " + assassinationContractsTable + " WHERE target_uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, targetUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("total");
            }
        } catch (SQLException e) {
            logSevere("Failed to get assassination contract total.", e);
        }
        return 0;
    }

    public void clearAssassinationContracts(UUID targetUuid) {
        Connection conn = getConnection();
        if (conn == null) return;
        String sql = "DELETE FROM " + assassinationContractsTable + " WHERE target_uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, targetUuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logSevere("Failed to clear assassination contracts.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Public hit contract persistence
    // -------------------------------------------------------------------------

    public boolean hasActiveHitContract(UUID targetUuid) {
        return getHitContractByTarget(targetUuid, System.currentTimeMillis()) != null;
    }

    public boolean insertHitContract(UUID requesterUuid, String requesterName, UUID targetUuid,
                                     String targetName, int amount, long createdAt, long expiresAt) {
        Connection conn = getConnection();
        if (conn == null) return false;

        String sql = "INSERT INTO " + hitContractsTable
                + " (requester_uuid, requester_name, target_uuid, target_name, amount, created_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, requesterUuid.toString());
            stmt.setString(2, requesterName);
            stmt.setString(3, targetUuid.toString());
            stmt.setString(4, targetName);
            stmt.setInt(5, amount);
            stmt.setLong(6, createdAt);
            stmt.setLong(7, expiresAt);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public AssassinationContract getHitContractByTarget(UUID targetUuid, long now) {
        Connection conn = getConnection();
        if (conn == null) return null;

        String sql = "SELECT * FROM " + hitContractsTable + " WHERE target_uuid = ? AND expires_at > ? LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, targetUuid.toString());
            stmt.setLong(2, now);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapHitContract(rs);
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to get hit contract by target.", e);
        }

        return null;
    }

    public AssassinationContract getAssignedHitContract(UUID hitmanUuid, long now) {
        Connection conn = getConnection();
        if (conn == null) return null;

        String sql = "SELECT * FROM " + hitContractsTable
                + " WHERE accepted_by_uuid = ? AND expires_at > ? ORDER BY accepted_at ASC LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hitmanUuid.toString());
            stmt.setLong(2, now);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapHitContract(rs);
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to get assigned hit contract.", e);
        }

        return null;
    }

    public List<AssassinationContract> getOpenHitContracts(long now) {
        Connection conn = getConnection();
        List<AssassinationContract> contracts = new ArrayList<>();
        if (conn == null) return contracts;

        String sql = "SELECT * FROM " + hitContractsTable
                + " WHERE accepted_by_uuid IS NULL AND expires_at > ? ORDER BY amount DESC";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, now);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    contracts.add(mapHitContract(rs));
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to get open hit contracts.", e);
        }

        return contracts;
    }

    public boolean acceptHitContract(long contractId, UUID hitmanUuid, String hitmanName, long acceptedAt) {
        Connection conn = getConnection();
        if (conn == null) return false;

        String sql = "UPDATE " + hitContractsTable
                + " SET accepted_by_uuid = ?, accepted_by_name = ?, accepted_at = ? "
                + "WHERE id = ? AND accepted_by_uuid IS NULL AND expires_at > ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hitmanUuid.toString());
            stmt.setString(2, hitmanName);
            stmt.setLong(3, acceptedAt);
            stmt.setLong(4, contractId);
            stmt.setLong(5, acceptedAt);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logSevere("Failed to accept hit contract.", e);
        }
        return false;
    }

    public List<AssassinationContract> getExpiredHitContracts(long now) {
        Connection conn = getConnection();
        List<AssassinationContract> contracts = new ArrayList<>();
        if (conn == null) return contracts;

        String sql = "SELECT * FROM " + hitContractsTable + " WHERE expires_at <= ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, now);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    contracts.add(mapHitContract(rs));
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to get expired hit contracts.", e);
        }

        return contracts;
    }

    public void deleteHitContract(long id) {
        Connection conn = getConnection();
        if (conn == null) return;

        String sql = "DELETE FROM " + hitContractsTable + " WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logSevere("Failed to delete hit contract.", e);
        }
    }

    private AssassinationContract mapHitContract(ResultSet rs) throws SQLException {
        String requesterUuidRaw = rs.getString("requester_uuid");
        String acceptedByUuidRaw = rs.getString("accepted_by_uuid");
        long acceptedAtRaw = rs.getLong("accepted_at");
        boolean acceptedAtMissing = rs.wasNull();

        UUID requesterUuid = UUID.fromString(requesterUuidRaw);
        UUID targetUuid = UUID.fromString(rs.getString("target_uuid"));
        UUID acceptedByUuid = acceptedByUuidRaw == null ? null : UUID.fromString(acceptedByUuidRaw);
        Long acceptedAt = acceptedAtMissing ? null : acceptedAtRaw;

        return new AssassinationContract(
                rs.getLong("id"),
                requesterUuid,
                rs.getString("requester_name"),
                targetUuid,
                rs.getString("target_name"),
                rs.getInt("amount"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                acceptedByUuid,
                rs.getString("accepted_by_name"),
                acceptedAt
        );
    }

    public synchronized Connection getConnection() {
        if (!initialized) {
            initialize();
            return connection;
        }

        if (!isConnectionUsable()) {
            if (!reconnectConnection()) {
                initialized = false;
                return null;
            }
        }

        return connection;
    }

    public synchronized void close() {
        closeConnectionQuietly();
        initialized = false;
    }

    private boolean isConnectionUsable() {
        if (connection == null) {
            return false;
        }

        try {
            if (connection.isClosed()) {
                return false;
            }

            if (!mysqlMode) {
                return true;
            }

            return connection.isValid(MYSQL_CONNECTION_VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException exception) {
            return false;
        }
    }

    private boolean reconnectConnection() {
        closeConnectionQuietly();
        return openConnection();
    }

    private void closeConnectionQuietly() {
        if (connection == null) {
            return;
        }

        try {
            connection.close();
        } catch (SQLException exception) {
            logSevere("Failed to close bounty database connection.", exception);
        } finally {
            connection = null;
        }
    }

    private boolean createTables() {
        String uuidType = mysqlMode ? "VARCHAR(36)" : "TEXT";
        String nameType = mysqlMode ? "VARCHAR(64)" : "TEXT";
        String autoIncrement = mysqlMode ? "INT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";

        String playersTableSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (player_uuid %s PRIMARY KEY, player_name %s NOT NULL, last_seen BIGINT NOT NULL)",
                playersTable,
                uuidType,
                nameType
        );
        String playerBountiesTableSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (player_uuid %s PRIMARY KEY, bounty INTEGER DEFAULT 0, kills INTEGER DEFAULT 0, deaths INTEGER DEFAULT 0)",
                playerBountiesTable,
                uuidType
        );
        String kingdomBountiesTableSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (kingdom_name %s PRIMARY KEY, bounty INTEGER DEFAULT 0)",
                kingdomBountiesTable,
                nameType
        );
        String placedBountiesTableSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (id %s, target_type VARCHAR(16), target_name %s, placed_by %s, amount INTEGER)",
                placedBountiesTable,
                autoIncrement,
                nameType,
                nameType
        );
        String killCooldownTableSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (killer_uuid %s, victim_uuid %s, timestamp BIGINT, PRIMARY KEY (killer_uuid, victim_uuid))",
                killCooldownTable,
                uuidType,
                uuidType
        );

    String warsTableSql = String.format(
        "CREATE TABLE IF NOT EXISTS %s (kingdom_a %s NOT NULL, kingdom_b %s NOT NULL, start_time BIGINT NOT NULL, active INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (kingdom_a, kingdom_b))",
        warsTable, nameType, nameType
    );
    String warKillsTableSql = String.format(
        "CREATE TABLE IF NOT EXISTS %s (id %s, killer_uuid %s NOT NULL, victim_uuid %s NOT NULL, kingdom_killer %s NOT NULL, kingdom_victim %s NOT NULL, timestamp BIGINT NOT NULL)",
        warKillsTable, autoIncrement, uuidType, uuidType, nameType, nameType
    );
    String assassinationContractsTableSql = String.format(
        "CREATE TABLE IF NOT EXISTS %s (id %s, target_uuid %s NOT NULL, placed_by %s NOT NULL, amount INTEGER NOT NULL, timestamp BIGINT NOT NULL)",
        assassinationContractsTable, autoIncrement, uuidType, nameType
    );
    String hitContractsTableSql = String.format(
        "CREATE TABLE IF NOT EXISTS %s (id %s, requester_uuid %s NOT NULL, requester_name %s NOT NULL, target_uuid %s NOT NULL UNIQUE, target_name %s NOT NULL, amount INTEGER NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, accepted_by_uuid %s NULL, accepted_by_name %s NULL, accepted_at BIGINT NULL)",
        hitContractsTable, autoIncrement, uuidType, nameType, uuidType, nameType, uuidType, nameType
    );

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(playersTableSql);
            stmt.execute(playerBountiesTableSql);
            stmt.execute(kingdomBountiesTableSql);
            stmt.execute(placedBountiesTableSql);
            stmt.execute(killCooldownTableSql);
        stmt.execute(warsTableSql);
        stmt.execute(warKillsTableSql);
        stmt.execute(assassinationContractsTableSql);
        stmt.execute(hitContractsTableSql);
            return true;
        } catch (SQLException exception) {
            logSevere("Failed to create bounty database tables.", exception);
            return false;
        }
    }

    public synchronized boolean resetAllData() {
        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            logWarning("Cannot reset bounty data because database connection is unavailable.");
            return false;
        }

        boolean foreignKeysDisabled = false;
        try (Statement statement = activeConnection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            foreignKeysDisabled = true;

            dropTableIfExists(statement, hitContractsTable);
            dropTableIfExists(statement, assassinationContractsTable);
            dropTableIfExists(statement, warKillsTable);
            dropTableIfExists(statement, warsTable);
            dropTableIfExists(statement, killCooldownTable);
            dropTableIfExists(statement, placedBountiesTable);
            dropTableIfExists(statement, kingdomBountiesTable);
            dropTableIfExists(statement, playerBountiesTable);
            dropTableIfExists(statement, playersTable);
            dropTableIfExists(statement, "website_purchase_jobs");
        } catch (SQLException exception) {
            logSevere("Failed to reset bounty database tables.", exception);
            return false;
        } finally {
            if (foreignKeysDisabled) {
                try (Statement statement = activeConnection.createStatement()) {
                    statement.execute("SET FOREIGN_KEY_CHECKS = 1");
                } catch (SQLException ignored) {
                    // Intentionally ignored to preserve original failure context.
                }
            }
        }

        killCooldownCache.clear();
        return createTables();
    }

    private void dropTableIfExists(Statement statement, String tableName) throws SQLException {
        statement.execute("DROP TABLE IF EXISTS " + tableName);
    }

    private void buildTableNames() {
        String prefix = sanitizePrefix(getConfigString("bounty-database.table-prefix", ""));
        playersTable = prefix + "players";
        playerBountiesTable = prefix + "player_bounties";
        kingdomBountiesTable = prefix + "kingdom_bounties";
        placedBountiesTable = prefix + "placed_bounties";
        killCooldownTable = prefix + "kill_cooldowns";
        warsTable = prefix + "wars";
        warKillsTable = prefix + "war_kills";
        assassinationContractsTable = prefix + "assassination_contracts";
        hitContractsTable = prefix + "assassination_hits";
    }

    private void configureMode() {
        configuredMode = getConfigString("bounty-database.mode", "mysql")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!configuredMode.equals("mysql")) {
            logWarning("bounty-database.mode='" + configuredMode + "' is no longer supported. Forcing MySQL.");
            configuredMode = "mysql";
        }
        mysqlMode = true;
        configureMySqlConnection();
    }

    private void configureMySqlConnection() {
        String host = getConfigString("bounty-database.host", "localhost");
        int port = getConfigInt("bounty-database.port", 3306);
        String databaseName = getConfigString("bounty-database.database", "kingdomsbounty");
        username = getConfigString("bounty-database.username", "root");
        password = getConfigString("bounty-database.password", "change-me");
        jdbcUrl = String.format(
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&tcpKeepAlive=true&connectTimeout=10000&socketTimeout=30000",
                host,
                port,
                databaseName
        );
    }

    private boolean openConnection() {
        closeConnectionQuietly();

        try {
            connection = DriverManager.getConnection(jdbcUrl, username, password);
            logConnectionSuccess("Connected to bounty MySQL database (" + jdbcUrl.replace("jdbc:mysql://", "") + ").");
            return true;
        } catch (SQLException mysqlException) {
            connection = null;
            logSevere("Failed to connect to bounty database.", mysqlException);
            return false;
        }
    }

    private void logConnectionSuccess(String message) {
        if (!hasLoggedConnectionSuccess) {
            logInfo(message);
            hasLoggedConnectionSuccess = true;
        }
    }

    private String sanitizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }

        String trimmed = prefix.trim();
        if (!trimmed.matches("[A-Za-z0-9_]*")) {
            logWarning("Ignoring invalid bounty table-prefix: '" + prefix + "'. Only letters, numbers, and underscore are allowed.");
            return "";
        }

        return trimmed;
    }

    private String getConfigString(String path, String defaultValue) {
        if (plugin == null) {
            return defaultValue;
        }

        String value = plugin.getConfig().getString(path);
        if ((value == null || value.isBlank()) && path.endsWith(".database")) {
            String aliasPath = path.substring(0, path.length() - ".database".length()) + ".name";
            value = plugin.getConfig().getString(aliasPath);
        }

        if ((value == null || value.isBlank()) && path.startsWith("bounty-database.")) {
            String suffix = path.substring("bounty-database.".length());
            value = plugin.getConfig().getString("database." + suffix);

            if ((value == null || value.isBlank()) && suffix.equals("database")) {
                value = plugin.getConfig().getString("database.name");
            }
        }

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
    }

    private int getConfigInt(String path, int defaultValue) {
        if (plugin == null) {
            return defaultValue;
        }

        if (plugin.getConfig().contains(path)) {
            return plugin.getConfig().getInt(path, defaultValue);
        }

        if (path.startsWith("bounty-database.")) {
            String suffix = path.substring("bounty-database.".length());
            return plugin.getConfig().getInt("database." + suffix, defaultValue);
        }

        return defaultValue;
    }

    private void logInfo(String message) {
        if (plugin != null) {
            plugin.getLogger().info(message);
        }
    }

    private void logWarning(String message) {
        if (plugin != null) {
            plugin.getLogger().warning(message);
        }
    }

    private void logSevere(String message, Exception exception) {
        if (plugin != null) {
            plugin.getLogger().log(Level.SEVERE, message, exception);
        } else {
            System.err.println(message);
            if (exception != null) {
                exception.printStackTrace();
            }
        }
    }
}
