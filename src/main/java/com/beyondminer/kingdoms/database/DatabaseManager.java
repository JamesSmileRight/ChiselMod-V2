package com.beyondminer.kingdoms.database;

import com.beyondminer.kingdoms.models.Kingdom;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles kingdom database operations for kingdoms and members.
 */
public class DatabaseManager {
    private final JavaPlugin plugin;
    private boolean mysqlMode;
    private String configuredMode;
    private String jdbcUrl;
    private String username;
    private String password;
    private String kingdomsTable;
    private String membersTable;
    private String alliesTable;
    private String allyRequestsTable;

    private boolean initialized;

    public DatabaseManager(File dataFolder) {
        this(dataFolder, null);
    }

    public DatabaseManager(File dataFolder, JavaPlugin plugin) {
        this.plugin = plugin;

        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        initializeDatabase();
    }

    public boolean isInitialized() {
        return initialized;
    }

    private void initializeDatabase() {
        initialized = false;
        configureTableNames();
        configureMode();

        try (Connection conn = openConfiguredConnection()) {
            logInfo("Connected to kingdoms " + describeConfiguredDatabase() + ".");
            initializeSchema(conn);
            initialized = true;
        } catch (SQLException mysqlException) {
            logSevere("Failed to initialize kingdoms database.", mysqlException);
        }
    }

    private Connection getConnection() throws SQLException {
        if (!initialized) {
            initializeDatabase();
        }

        return openConfiguredConnection();
    }

    /**
     * Saves a kingdom to the database.
     */
    public void saveKingdom(Kingdom kingdom) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                String query;
                if (mysqlMode) {
                    query = "INSERT INTO " + kingdomsTable + " (name, leader, color, created_at, capital_world, capital_x, capital_y, capital_z, capital_yaw, capital_pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE leader = VALUES(leader), color = VALUES(color), created_at = VALUES(created_at), capital_world = VALUES(capital_world), capital_x = VALUES(capital_x), capital_y = VALUES(capital_y), capital_z = VALUES(capital_z), capital_yaw = VALUES(capital_yaw), capital_pitch = VALUES(capital_pitch)";
                } else {
                    query = "INSERT INTO " + kingdomsTable + " (name, leader, color, created_at, capital_world, capital_x, capital_y, capital_z, capital_yaw, capital_pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT(name) DO UPDATE SET leader = excluded.leader, color = excluded.color, created_at = excluded.created_at, capital_world = excluded.capital_world, capital_x = excluded.capital_x, capital_y = excluded.capital_y, capital_z = excluded.capital_z, capital_yaw = excluded.capital_yaw, capital_pitch = excluded.capital_pitch";
                }

                try (PreparedStatement stmt = conn.prepareStatement(query)) {
                    stmt.setString(1, kingdom.getName());
                    stmt.setString(2, kingdom.getLeader().toString());
                    stmt.setString(3, kingdom.getColor());
                    stmt.setLong(4, kingdom.getCreatedAt());
                    stmt.setString(5, kingdom.getCapitalWorld());
                    stmt.setDouble(6, kingdom.getCapitalX());
                    stmt.setDouble(7, kingdom.getCapitalY());
                    stmt.setDouble(8, kingdom.getCapitalZ());
                    stmt.setFloat(9, kingdom.getCapitalYaw());
                    stmt.setFloat(10, kingdom.getCapitalPitch());
                    stmt.executeUpdate();
                }

                // Insert members (delete old ones first)
                String deleteMembers = "DELETE FROM " + membersTable + " WHERE kingdom = ?";
                try (PreparedStatement stmt = conn.prepareStatement(deleteMembers)) {
                    stmt.setString(1, kingdom.getName());
                    stmt.executeUpdate();
                }

                String insertMembers = "INSERT INTO " + membersTable + " (player_uuid, kingdom) VALUES (?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(insertMembers)) {
                    for (UUID member : kingdom.getMembers()) {
                        stmt.setString(1, member.toString());
                        stmt.setString(2, kingdom.getName());
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logSevere("Failed to save kingdom.", e);
        }
    }

    /**
     * Deletes a kingdom from the database.
     */
    public void deleteKingdom(String kingdomName) {
        try (Connection conn = getConnection()) {
            clearAlliesForKingdom(kingdomName);

            // Delete members first
            String deleteMembers = "DELETE FROM " + membersTable + " WHERE kingdom = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteMembers)) {
                stmt.setString(1, kingdomName);
                stmt.executeUpdate();
            }

            // Delete kingdom
            String deleteKingdom = "DELETE FROM " + kingdomsTable + " WHERE name = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteKingdom)) {
                stmt.setString(1, kingdomName);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            logSevere("Failed to delete kingdom.", e);
        }
    }

    /**
     * Loads all kingdoms from the database.
     */
    public Map<String, Kingdom> loadAllKingdoms() {
        Map<String, Kingdom> kingdoms = new HashMap<>();

        try (Connection conn = getConnection()) {
                String query = "SELECT name, leader, color, created_at, capital_world, capital_x, capital_y, capital_z, capital_yaw, capital_pitch FROM " + kingdomsTable;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    UUID leader = UUID.fromString(rs.getString("leader"));
                    String color = rs.getString("color");
                    long createdAt = rs.getLong("created_at");
                    Kingdom kingdom = new Kingdom(
                        name,
                        leader,
                        color,
                        createdAt,
                        rs.getString("capital_world"),
                        rs.getDouble("capital_x"),
                        rs.getDouble("capital_y"),
                        rs.getDouble("capital_z"),
                        rs.getFloat("capital_yaw"),
                        rs.getFloat("capital_pitch")
                    );

                    // Load members
                    String membersQuery = "SELECT player_uuid FROM " + membersTable + " WHERE kingdom = ?";
                    try (PreparedStatement pstmt = conn.prepareStatement(membersQuery)) {
                        pstmt.setString(1, name);
                        try (ResultSet memberRs = pstmt.executeQuery()) {
                            while (memberRs.next()) {
                                UUID memberUUID = UUID.fromString(memberRs.getString("player_uuid"));
                                if (!memberUUID.equals(leader)) {
                                    kingdom.addMember(memberUUID);
                                }
                            }
                        }
                    }

                    kingdoms.put(name, kingdom);
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to load kingdoms.", e);
        }

        return kingdoms;
    }

    /**
     * Gets a player's kingdom name.
     */
    public String getPlayerKingdom(UUID playerUUID) {
        try (Connection conn = getConnection()) {
            String query = "SELECT kingdom FROM " + membersTable + " WHERE player_uuid = ? LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, playerUUID.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("kingdom");
                    }
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to get player kingdom.", e);
        }

        return null;
    }

    public boolean areAllied(String kingdom1, String kingdom2) {
        if (kingdom1 == null || kingdom2 == null || kingdom1.equalsIgnoreCase(kingdom2)) {
            return false;
        }

        String[] ordered = orderedPair(kingdom1, kingdom2);
        try (Connection conn = getConnection()) {
            String query = "SELECT 1 FROM " + alliesTable + " WHERE kingdom_a = ? AND kingdom_b = ? LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, ordered[0]);
                stmt.setString(2, ordered[1]);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to check alliance status.", e);
        }

        return false;
    }

    public int getAllyCount(String kingdomName) {
        if (kingdomName == null || kingdomName.isBlank()) {
            return 0;
        }

        try (Connection conn = getConnection()) {
            String query = "SELECT COUNT(*) AS total FROM " + alliesTable + " WHERE kingdom_a = ? OR kingdom_b = ?";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, kingdomName);
                stmt.setString(2, kingdomName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("total");
                    }
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to count allies.", e);
        }

        return 0;
    }

    public List<String> getAllies(String kingdomName) {
        List<String> allies = new ArrayList<>();
        if (kingdomName == null || kingdomName.isBlank()) {
            return allies;
        }

        try (Connection conn = getConnection()) {
            String query = "SELECT kingdom_a, kingdom_b FROM " + alliesTable + " WHERE kingdom_a = ? OR kingdom_b = ?";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, kingdomName);
                stmt.setString(2, kingdomName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String a = rs.getString("kingdom_a");
                        String b = rs.getString("kingdom_b");
                        if (kingdomName.equalsIgnoreCase(a)) {
                            allies.add(b);
                        } else {
                            allies.add(a);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to fetch allies.", e);
        }

        return allies;
    }

    public List<String> getIncomingAllyRequests(String kingdomName) {
        List<String> requests = new ArrayList<>();
        if (kingdomName == null || kingdomName.isBlank()) {
            return requests;
        }

        try (Connection conn = getConnection()) {
            String query = "SELECT from_kingdom FROM " + allyRequestsTable + " WHERE to_kingdom = ? ORDER BY created_at ASC";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, kingdomName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        requests.add(rs.getString("from_kingdom"));
                    }
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to fetch incoming ally requests.", e);
        }

        return requests;
    }

    public boolean hasPendingAllyRequest(String fromKingdom, String toKingdom) {
        if (fromKingdom == null || toKingdom == null) {
            return false;
        }

        try (Connection conn = getConnection()) {
            String query = "SELECT 1 FROM " + allyRequestsTable + " WHERE from_kingdom = ? AND to_kingdom = ? LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, fromKingdom);
                stmt.setString(2, toKingdom);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            logSevere("Failed to check ally request.", e);
        }

        return false;
    }

    public boolean insertAllyRequest(String fromKingdom, String toKingdom) {
        if (fromKingdom == null || toKingdom == null) {
            return false;
        }

        try (Connection conn = getConnection()) {
            String insert = "INSERT INTO " + allyRequestsTable + " (from_kingdom, to_kingdom, created_at) VALUES (?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insert)) {
                stmt.setString(1, fromKingdom);
                stmt.setString(2, toKingdom);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean acceptAllyRequest(String fromKingdom, String toKingdom) {
        if (fromKingdom == null || toKingdom == null) {
            return false;
        }

        String[] ordered = orderedPair(fromKingdom, toKingdom);

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                String deleteRequest = "DELETE FROM " + allyRequestsTable + " WHERE from_kingdom = ? AND to_kingdom = ?";
                int removed;
                try (PreparedStatement deleteStmt = conn.prepareStatement(deleteRequest)) {
                    deleteStmt.setString(1, fromKingdom);
                    deleteStmt.setString(2, toKingdom);
                    removed = deleteStmt.executeUpdate();
                }

                if (removed <= 0) {
                    conn.rollback();
                    return false;
                }

                String deleteReverseRequest = "DELETE FROM " + allyRequestsTable + " WHERE from_kingdom = ? AND to_kingdom = ?";
                try (PreparedStatement reverseStmt = conn.prepareStatement(deleteReverseRequest)) {
                    reverseStmt.setString(1, toKingdom);
                    reverseStmt.setString(2, fromKingdom);
                    reverseStmt.executeUpdate();
                }

                String insertAlly = "INSERT INTO " + alliesTable + " (kingdom_a, kingdom_b, created_at) VALUES (?, ?, ?)";
                try (PreparedStatement insertStmt = conn.prepareStatement(insertAlly)) {
                    insertStmt.setString(1, ordered[0]);
                    insertStmt.setString(2, ordered[1]);
                    insertStmt.setLong(3, System.currentTimeMillis());
                    insertStmt.executeUpdate();
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logSevere("Failed to accept ally request.", e);
        }

        return false;
    }

    public boolean removeAlly(String kingdom1, String kingdom2) {
        if (kingdom1 == null || kingdom2 == null) {
            return false;
        }

        String[] ordered = orderedPair(kingdom1, kingdom2);

        try (Connection conn = getConnection()) {
            String delete = "DELETE FROM " + alliesTable + " WHERE kingdom_a = ? AND kingdom_b = ?";
            try (PreparedStatement stmt = conn.prepareStatement(delete)) {
                stmt.setString(1, ordered[0]);
                stmt.setString(2, ordered[1]);
                return stmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            logSevere("Failed to remove alliance.", e);
        }

        return false;
    }

    public void clearAlliesForKingdom(String kingdomName) {
        if (kingdomName == null || kingdomName.isBlank()) {
            return;
        }

        try (Connection conn = getConnection()) {
            String deleteAllies = "DELETE FROM " + alliesTable + " WHERE kingdom_a = ? OR kingdom_b = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteAllies)) {
                stmt.setString(1, kingdomName);
                stmt.setString(2, kingdomName);
                stmt.executeUpdate();
            }

            String deleteRequests = "DELETE FROM " + allyRequestsTable + " WHERE from_kingdom = ? OR to_kingdom = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteRequests)) {
                stmt.setString(1, kingdomName);
                stmt.setString(2, kingdomName);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            logSevere("Failed to clear alliances for kingdom.", e);
        }
    }

    private String sanitizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }

        String trimmed = prefix.trim();
        if (!trimmed.matches("[A-Za-z0-9_]*")) {
            logWarning("Ignoring invalid kingdoms table-prefix: '" + prefix + "'. Only letters, numbers, and underscore are allowed.");
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

        if ((value == null || value.isBlank()) && path.startsWith("kingdoms-database.")) {
            String suffix = path.substring("kingdoms-database.".length());
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

        if (path.startsWith("kingdoms-database.")) {
            String suffix = path.substring("kingdoms-database.".length());
            return plugin.getConfig().getInt("database." + suffix, defaultValue);
        }

        return defaultValue;
    }

    private void configureTableNames() {
        String prefix = sanitizePrefix(getConfigString("kingdoms-database.table-prefix", ""));
        kingdomsTable = prefix + "kingdoms";
        membersTable = prefix + "kingdom_members";
        alliesTable = prefix + "kingdom_allies";
        allyRequestsTable = prefix + "kingdom_ally_requests";
    }

    private void configureMode() {
        configuredMode = getConfigString("kingdoms-database.mode", "mysql")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!configuredMode.equals("mysql")) {
            logWarning("kingdoms-database.mode='" + configuredMode + "' is no longer supported. Forcing MySQL.");
            configuredMode = "mysql";
        }

        mysqlMode = true;
        configureMySqlConnection();
    }

    private Connection openConfiguredConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void configureMySqlConnection() {
        String host = getConfigString("kingdoms-database.host", "localhost");
        int port = getConfigInt("kingdoms-database.port", 3306);
        String databaseName = getConfigString("kingdoms-database.database", "kingdomsbounty");
        username = getConfigString("kingdoms-database.username", "root");
        password = getConfigString("kingdoms-database.password", "change-me");
        jdbcUrl = String.format(
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&tcpKeepAlive=true&connectTimeout=10000&socketTimeout=30000",
                host,
                port,
                databaseName
        );
    }

    private String describeConfiguredDatabase() {
        if (mysqlMode) {
            return "MySQL database (" + jdbcUrl.replace("jdbc:mysql://", "") + ")";
        }
        return "SQLite database (" + jdbcUrl.replace("jdbc:sqlite:", "") + ")";
    }

    public synchronized boolean resetAllData() {
        if (!initialized) {
            initializeDatabase();
            if (!initialized) {
                return false;
            }
        }

        try (Connection conn = openConfiguredConnection()) {
            boolean foreignKeysDisabled = false;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
                foreignKeysDisabled = true;

                dropTableIfExists(stmt, allyRequestsTable);
                dropTableIfExists(stmt, alliesTable);
                dropTableIfExists(stmt, membersTable);
                dropTableIfExists(stmt, kingdomsTable);
            } finally {
                if (foreignKeysDisabled) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
                    } catch (SQLException ignored) {
                        // Intentionally ignored to preserve original failure context.
                    }
                }
            }

            initializeSchema(conn);
            return true;
        } catch (SQLException exception) {
            logSevere("Failed to reset kingdoms database.", exception);
            return false;
        }
    }

    private void dropTableIfExists(Statement stmt, String tableName) throws SQLException {
        stmt.execute("DROP TABLE IF EXISTS " + tableName);
    }

    private void initializeSchema(Connection conn) throws SQLException {
        String createKingdomsTable = "CREATE TABLE IF NOT EXISTS " + kingdomsTable + " (" +
                "name VARCHAR(64) PRIMARY KEY," +
                "leader VARCHAR(36) NOT NULL," +
                "color VARCHAR(32) NOT NULL," +
                "created_at BIGINT NOT NULL," +
                "capital_world VARCHAR(64) NULL," +
                "capital_x DOUBLE NOT NULL DEFAULT 0," +
                "capital_y DOUBLE NOT NULL DEFAULT 0," +
                "capital_z DOUBLE NOT NULL DEFAULT 0," +
                "capital_yaw FLOAT NOT NULL DEFAULT 0," +
                "capital_pitch FLOAT NOT NULL DEFAULT 0" +
                ")";
        String createMembersTable = "CREATE TABLE IF NOT EXISTS " + membersTable + " (" +
                "player_uuid VARCHAR(36) PRIMARY KEY," +
                "kingdom VARCHAR(64) NOT NULL," +
                "FOREIGN KEY (kingdom) REFERENCES " + kingdomsTable + "(name) ON DELETE CASCADE" +
                ")";

        String createAlliesTable = "CREATE TABLE IF NOT EXISTS " + alliesTable + " (" +
            "kingdom_a VARCHAR(64) NOT NULL," +
            "kingdom_b VARCHAR(64) NOT NULL," +
            "created_at BIGINT NOT NULL," +
            "PRIMARY KEY (kingdom_a, kingdom_b)" +
            ")";

        String createAllyRequestsTable = "CREATE TABLE IF NOT EXISTS " + allyRequestsTable + " (" +
            "from_kingdom VARCHAR(64) NOT NULL," +
            "to_kingdom VARCHAR(64) NOT NULL," +
            "created_at BIGINT NOT NULL," +
            "PRIMARY KEY (from_kingdom, to_kingdom)" +
            ")";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createKingdomsTable);
            stmt.execute(createMembersTable);
            stmt.execute(createAlliesTable);
            stmt.execute(createAllyRequestsTable);
        }

        ensureColumnExists(conn, kingdomsTable, "color", "VARCHAR(32) NOT NULL DEFAULT 'gold'");
        ensureColumnExists(conn, kingdomsTable, "created_at", "BIGINT NOT NULL DEFAULT 0");
        ensureColumnExists(conn, kingdomsTable, "capital_world", "VARCHAR(64) NULL");
        ensureColumnExists(conn, kingdomsTable, "capital_x", "DOUBLE NOT NULL DEFAULT 0");
        ensureColumnExists(conn, kingdomsTable, "capital_y", "DOUBLE NOT NULL DEFAULT 0");
        ensureColumnExists(conn, kingdomsTable, "capital_z", "DOUBLE NOT NULL DEFAULT 0");
        ensureColumnExists(conn, kingdomsTable, "capital_yaw", "FLOAT NOT NULL DEFAULT 0");
        ensureColumnExists(conn, kingdomsTable, "capital_pitch", "FLOAT NOT NULL DEFAULT 0");
    }

    private void ensureColumnExists(Connection conn, String tableName, String columnName, String definition) {
        try {
            if (hasColumn(conn, tableName, columnName)) {
                return;
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
            }
        } catch (SQLException exception) {
            logSevere("Failed to ensure kingdoms schema column '" + columnName + "'.", exception);
        }
    }

    private boolean hasColumn(Connection conn, String tableName, String columnName) throws SQLException {
        try (ResultSet columns = conn.getMetaData().getColumns(null, null, tableName, columnName)) {
            return columns.next();
        }
    }

    private String[] orderedPair(String first, String second) {
        String normalizedFirst = first.toLowerCase(Locale.ROOT);
        String normalizedSecond = second.toLowerCase(Locale.ROOT);
        if (normalizedFirst.compareTo(normalizedSecond) <= 0) {
            return new String[] { first, second };
        }
        return new String[] { second, first };
    }

    private void logWarning(String message) {
        if (plugin != null) {
            plugin.getLogger().warning(message);
        }
    }

    private void logInfo(String message) {
        if (plugin != null) {
            plugin.getLogger().info(message);
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
