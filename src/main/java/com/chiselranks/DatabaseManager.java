package com.chiselranks;

import com.chiselranks.rank.Rank;
import com.chiselranks.staff.StaffRole;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class DatabaseManager {
    private final ChiselRanksPlugin plugin;
    private final ExecutorService executor;

    private volatile boolean available;
    private String jdbcUrl;
    private String username;
    private String password;

    public DatabaseManager(ChiselRanksPlugin plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(new DatabaseThreadFactory());
    }

    public boolean initialize() {
        String host = plugin.getConfig().getString("database.host", "srv1947.hstgr.io");
        int port = plugin.getConfig().getInt("database.port", 3306);
        String database = plugin.getConfig().getString("database.name", "u787705592_chiselmods");
        username = plugin.getConfig().getString("database.username", "u787705592_chisel");
        password = plugin.getConfig().getString("database.password", "");

        jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false"
                + "&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8"
                + "&useUnicode=true"
                + "&serverTimezone=UTC"
                + "&tcpKeepAlive=true"
                + "&connectTimeout=10000"
                + "&socketTimeout=15000";

        plugin.getLogger().info("[ChiselRanks] Connecting to MySQL...");
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");

            // Validate credentials/connectivity before marking the manager available.
            try (Connection ignored = openConnection()) {
                // No-op
            }

            createTables();
            available = true;
            plugin.getLogger().info("[ChiselRanks] Successfully connected to database");
            return true;
        } catch (SQLException | ClassNotFoundException exception) {
            available = false;
            plugin.getLogger().severe("[ChiselRanks] Failed to connect to MySQL: " + exception.getMessage());
            return false;
        }
    }

    public Connection getConnection() throws SQLException {
        return openConnection();
    }

    public boolean isAvailable() {
        return available;
    }

    public CompletableFuture<TrailRecord> loadTrailPreference(UUID uuid) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(new TrailRecord(false, "none"));
        }

        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT enabled, trail_type FROM player_trails WHERE uuid = ?";
            try (Connection active = openConnection();
                 PreparedStatement statement = active.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return new TrailRecord(resultSet.getBoolean("enabled"), resultSet.getString("trail_type"));
                    }
                }
            } catch (SQLException exception) {
                logSqlWarning("load trail preference for " + uuid, exception);
            }

            return new TrailRecord(false, "none");
        }, executor);
    }

    public void saveTrailPreference(UUID uuid, boolean enabled, String trailType) {
        if (!isAvailable()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_trails (uuid, enabled, trail_type) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), trail_type = VALUES(trail_type)";
            try (Connection active = openConnection();
                 PreparedStatement statement = active.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setBoolean(2, enabled);
                statement.setString(3, trailType);
                statement.executeUpdate();
            } catch (SQLException exception) {
                logSqlWarning("save trail preference for " + uuid, exception);
            }
        }, executor);
    }

    public void syncPlayerRank(UUID uuid, Rank rank) {
        savePlayerRank(uuid, rank);
    }

    public CompletableFuture<Rank> loadPlayerRank(UUID uuid) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(Rank.NONE);
        }

        return CompletableFuture.supplyAsync(() -> loadPlayerRankSync(uuid), executor);
    }

    public Rank loadPlayerRankSync(UUID uuid) {
        if (!isAvailable()) {
            return Rank.NONE;
        }

        String sql = "SELECT rank FROM player_ranks WHERE uuid = ?";
        try (Connection active = openConnection();
             PreparedStatement statement = active.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Rank.fromKey(resultSet.getString("rank")).orElse(Rank.NONE);
                }
            }
        } catch (SQLException exception) {
            logSqlWarning("load rank for " + uuid, exception);
        }

        return Rank.NONE;
    }

    public void savePlayerRank(UUID uuid, Rank rank) {
        if (!isAvailable()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            if (rank == Rank.NONE) {
                deletePlayerRank(uuid);
                return;
            }

            String sql = "INSERT INTO player_ranks (uuid, rank, purchase_date) VALUES (?, ?, NOW()) "
                    + "ON DUPLICATE KEY UPDATE rank = VALUES(rank), purchase_date = NOW()";
            try (Connection active = openConnection();
                 PreparedStatement statement = active.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, rank.getKey());
                statement.executeUpdate();
            } catch (SQLException exception) {
                logSqlWarning("save rank for " + uuid, exception);
            }
        }, executor);
    }

    public CompletableFuture<StaffRole> loadPlayerStaffRole(UUID uuid) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(StaffRole.NONE);
        }

        return CompletableFuture.supplyAsync(() -> loadPlayerStaffRoleSync(uuid), executor);
    }

    public StaffRole loadPlayerStaffRoleSync(UUID uuid) {
        if (!isAvailable()) {
            return StaffRole.NONE;
        }

        String sql = "SELECT role FROM player_staff_roles WHERE uuid = ?";
        try (Connection active = openConnection();
             PreparedStatement statement = active.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return StaffRole.fromKey(resultSet.getString("role")).orElse(StaffRole.NONE);
                }
            }
        } catch (SQLException exception) {
            logSqlWarning("load staff role for " + uuid, exception);
        }

        return StaffRole.NONE;
    }

    public void savePlayerStaffRole(UUID uuid, StaffRole role) {
        if (!isAvailable()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            if (role == null || role == StaffRole.NONE) {
                deletePlayerStaffRole(uuid);
                return;
            }

            String sql = "INSERT INTO player_staff_roles (uuid, role, updated_at) VALUES (?, ?, NOW()) "
                    + "ON DUPLICATE KEY UPDATE role = VALUES(role), updated_at = NOW()";
            try (Connection active = openConnection();
                 PreparedStatement statement = active.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, role.getKey());
                statement.executeUpdate();
            } catch (SQLException exception) {
                logSqlWarning("save staff role for " + uuid, exception);
            }
        }, executor);
    }

    public CompletableFuture<Map<String, HomeRecord>> loadHomes(UUID uuid) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        return CompletableFuture.supplyAsync(() -> loadHomesSync(uuid), executor);
    }

    public Map<String, HomeRecord> loadHomesSync(UUID uuid) {
        if (!isAvailable()) {
            return Map.of();
        }

        Map<String, HomeRecord> homes = new LinkedHashMap<>();
        String sql = "SELECT home_name, world, x, y, z, yaw, pitch FROM player_homes WHERE uuid = ? ORDER BY home_name ASC";
        try (Connection active = openConnection();
             PreparedStatement statement = active.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    HomeRecord record = new HomeRecord(
                            resultSet.getString("home_name"),
                            resultSet.getString("world"),
                            resultSet.getDouble("x"),
                            resultSet.getDouble("y"),
                            resultSet.getDouble("z"),
                            resultSet.getFloat("yaw"),
                            resultSet.getFloat("pitch")
                    );
                    homes.put(record.name().toLowerCase(Locale.ROOT), record);
                }
            }
        } catch (SQLException exception) {
            logSqlWarning("load homes for " + uuid, exception);
        }

        return homes;
    }

    public CompletableFuture<Integer> countHomes(UUID uuid) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(0);
        }

        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM player_homes WHERE uuid = ?";
            try (Connection active = openConnection();
                 PreparedStatement statement = active.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt(1);
                    }
                }
            } catch (SQLException exception) {
                logSqlWarning("count homes for " + uuid, exception);
            }
            return 0;
        }, executor);
    }

    public CompletableFuture<Void> saveHome(UUID uuid, String homeName, Location location) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_homes (uuid, home_name, world, x, y, z, yaw, pitch, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW()) "
                    + "ON DUPLICATE KEY UPDATE world = VALUES(world), x = VALUES(x), y = VALUES(y), z = VALUES(z), yaw = VALUES(yaw), pitch = VALUES(pitch), updated_at = NOW()";
            try (Connection active = openConnection();
                 PreparedStatement statement = active.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, normalizeHomeName(homeName));
                statement.setString(3, location.getWorld() == null ? "world" : location.getWorld().getName());
                statement.setDouble(4, location.getX());
                statement.setDouble(5, location.getY());
                statement.setDouble(6, location.getZ());
                statement.setFloat(7, location.getYaw());
                statement.setFloat(8, location.getPitch());
                statement.executeUpdate();
            } catch (SQLException exception) {
                logSqlWarning("save home for " + uuid, exception);
            }
        }, executor);
    }

    public CompletableFuture<Boolean> deleteHome(UUID uuid, String homeName) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM player_homes WHERE uuid = ? AND LOWER(home_name) = ?";
            try (Connection active = openConnection();
                 PreparedStatement statement = active.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, normalizeHomeName(homeName).toLowerCase(Locale.ROOT));
                return statement.executeUpdate() > 0;
            } catch (SQLException exception) {
                logSqlWarning("delete home for " + uuid, exception);
                return false;
            }
        }, executor);
    }

    public CompletableFuture<MessagePreferences> loadMessagePreferences(UUID uuid) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(MessagePreferences.defaults());
        }

        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT join_message_id, leave_message_id, death_message_id FROM player_message_preferences WHERE uuid = ?";
            try (Connection active = openConnection();
                 PreparedStatement statement = active.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return new MessagePreferences(
                                Math.max(1, resultSet.getInt("join_message_id")),
                                Math.max(1, resultSet.getInt("leave_message_id")),
                                Math.max(1, resultSet.getInt("death_message_id"))
                        );
                    }
                }
            } catch (SQLException exception) {
                logSqlWarning("load message preferences for " + uuid, exception);
            }
            return MessagePreferences.defaults();
        }, executor);
    }

    public void saveMessagePreference(UUID uuid, MessageSlot slot, int messageId) {
        if (!isAvailable()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_message_preferences (uuid, join_message_id, leave_message_id, death_message_id) VALUES (?, 1, 1, 1) "
                    + "ON DUPLICATE KEY UPDATE uuid = VALUES(uuid)";
            try (Connection active = openConnection()) {
                try (PreparedStatement insert = active.prepareStatement(sql)) {
                    insert.setString(1, uuid.toString());
                    insert.executeUpdate();
                }

                String column = switch (slot) {
                    case JOIN -> "join_message_id";
                    case LEAVE -> "leave_message_id";
                    case DEATH -> "death_message_id";
                };
                try (PreparedStatement update = active.prepareStatement("UPDATE player_message_preferences SET " + column + " = ? WHERE uuid = ?")) {
                    update.setInt(1, Math.max(1, messageId));
                    update.setString(2, uuid.toString());
                    update.executeUpdate();
                }
            } catch (SQLException exception) {
                logSqlWarning("save message preference for " + uuid, exception);
            }
        }, executor);
    }

    public synchronized boolean resetAllData() {
        if (!isAvailable() && !initialize()) {
            return false;
        }

        try (Connection active = openConnection();
             Statement statement = active.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS player_trails");
            statement.executeUpdate("DROP TABLE IF EXISTS player_homes");
            statement.executeUpdate("DROP TABLE IF EXISTS player_staff_roles");
            statement.executeUpdate("DROP TABLE IF EXISTS player_message_preferences");
            statement.executeUpdate("DROP TABLE IF EXISTS player_ranks");
            createTables();
            return true;
        } catch (SQLException exception) {
            logSqlWarning("reset ChiselRanks data", exception);
            return false;
        }
    }

    public void shutdown() {
        available = false;
        executor.shutdownNow();
        jdbcUrl = null;
        username = null;
        password = null;
    }

    private Connection openConnection() throws SQLException {
        if (jdbcUrl == null || username == null || password == null) {
            throw new SQLException("Database manager is not initialized");
        }
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void createTables() throws SQLException {
        try (Connection active = openConnection();
             Statement statement = active.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_ranks ("
                            + "uuid VARCHAR(36) PRIMARY KEY,"
                            + "rank VARCHAR(20),"
                            + "purchase_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                            + ")"
            );
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_trails ("
                            + "uuid VARCHAR(36) PRIMARY KEY,"
                            + "enabled BOOLEAN DEFAULT FALSE,"
                            + "trail_type VARCHAR(20) DEFAULT 'none'"
                            + ")"
            );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_staff_roles ("
                        + "uuid VARCHAR(36) PRIMARY KEY,"
                        + "role VARCHAR(20),"
                        + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                        + ")"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_homes ("
                        + "uuid VARCHAR(36) NOT NULL,"
                        + "home_name VARCHAR(32) NOT NULL,"
                        + "world VARCHAR(64) NOT NULL,"
                        + "x DOUBLE NOT NULL,"
                        + "y DOUBLE NOT NULL,"
                        + "z DOUBLE NOT NULL,"
                        + "yaw FLOAT NOT NULL DEFAULT 0,"
                        + "pitch FLOAT NOT NULL DEFAULT 0,"
                        + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (uuid, home_name)"
                        + ")"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_message_preferences ("
                        + "uuid VARCHAR(36) PRIMARY KEY,"
                        + "join_message_id INT DEFAULT 1,"
                        + "leave_message_id INT DEFAULT 1,"
                        + "death_message_id INT DEFAULT 1"
                        + ")"
                );
        }
    }

    private void deletePlayerRank(UUID uuid) {
        String sql = "DELETE FROM player_ranks WHERE uuid = ?";
        try (Connection active = openConnection();
             PreparedStatement statement = active.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            logSqlWarning("clear rank for " + uuid, exception);
        }
    }

    private void deletePlayerStaffRole(UUID uuid) {
        String sql = "DELETE FROM player_staff_roles WHERE uuid = ?";
        try (Connection active = openConnection();
             PreparedStatement statement = active.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            logSqlWarning("clear staff role for " + uuid, exception);
        }
    }

    private String normalizeHomeName(String homeName) {
        if (homeName == null || homeName.isBlank()) {
            return "home";
        }

        String trimmed = homeName.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() <= 32) {
            return trimmed;
        }
        return trimmed.substring(0, 32);
    }

    private void logSqlWarning(String action, SQLException exception) {
        plugin.getLogger().warning("Failed to " + action + ": " + exception.getMessage());
        if (isConnectionIssue(exception)) {
            plugin.getLogger().fine("[ChiselRanks] Connection issue detected during DB operation. The next operation will use a fresh MySQL connection.");
        }
    }

    private boolean isConnectionIssue(SQLException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("wait_timeout")
                || normalized.contains("no operations allowed after connection closed")
                || normalized.contains("communications link failure")
                || normalized.contains("connection is closed")
                || normalized.contains("broken pipe");
    }

    public record TrailRecord(boolean enabled, String trailType) {
    }

    public record HomeRecord(String name, String world, double x, double y, double z, float yaw, float pitch) {
        public Location toLocation() {
            World resolvedWorld = Bukkit.getWorld(world);
            if (resolvedWorld == null) {
                return null;
            }
            return new Location(resolvedWorld, x, y, z, yaw, pitch);
        }
    }

    public record MessagePreferences(int joinMessageId, int leaveMessageId, int deathMessageId) {
        public static MessagePreferences defaults() {
            return new MessagePreferences(1, 1, 1);
        }
    }

    public enum MessageSlot {
        JOIN,
        LEAVE,
        DEATH
    }

    private static final class DatabaseThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ChiselRanks-Database");
            thread.setDaemon(true);
            return thread;
        }
    }
}