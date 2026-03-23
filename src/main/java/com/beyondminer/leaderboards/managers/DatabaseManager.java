package com.beyondminer.leaderboards.managers;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class DatabaseManager {

    private static final long RECOVERY_BACKOFF_MS = 10000L;
    private static final long FAILURE_LOG_COOLDOWN_MS = 30000L;

    private final JavaPlugin plugin;
    private HikariDataSource dataSource;
    private String playersTable;
    private String playerBountiesTable;
    private String kingdomsTable;
    private String kingdomMembersTable;
    private String kingdomBountiesTable;
    private String databaseTarget;
    private long lastRecoveryAttemptAt;
    private long lastFailureLogAt;
    private boolean databaseHealthy;
    private boolean poolConfigLogged;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean initialize() {
        close();
        buildTableNames();

        String configuredMode = plugin.getConfig().getString("database.mode", "mysql");
        if (configuredMode == null || !configuredMode.trim().equalsIgnoreCase("mysql")) {
            plugin.getLogger().warning("database.mode='" + configuredMode + "' is no longer supported for leaderboards. Forcing MySQL.");
        }

        return initializeMySql();
    }

    private boolean initializeMySql() {
        String host = plugin.getConfig().getString("database.host", "localhost");
        int port = plugin.getConfig().getInt("database.port", 3306);
        String database = plugin.getConfig().getString("database.database");
        if (database == null || database.isBlank()) {
            database = plugin.getConfig().getString("database.name", "bounties");
        }
        String username = plugin.getConfig().getString("database.username", "root");
        String password = plugin.getConfig().getString("database.password", "");
        databaseTarget = host + ":" + port + "/" + database;

        HikariConfig config = new HikariConfig();
        config.setPoolName("BountyLeaderboardsPool");
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC",
                host,
                port,
                database
        ));
        config.setUsername(username);
        config.setPassword(password);
        int maxPoolSize = Math.max(2, plugin.getConfig().getInt("leaderboards.pool.max-size", 2));
        int minIdle = Math.max(0, plugin.getConfig().getInt("leaderboards.pool.min-idle", 0));
        if (minIdle >= maxPoolSize) {
            minIdle = Math.max(0, maxPoolSize - 1);
        }

        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(10000);
        config.setValidationTimeout(5000);
        config.setConnectionTestQuery("SELECT 1");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        config.addDataSourceProperty("connectTimeout", "10000");
        config.addDataSourceProperty("socketTimeout", "30000");

        long idleTimeoutMs = Math.max(10000L, plugin.getConfig().getLong("leaderboards.pool.idle-timeout-ms", 15000L));
        long configuredMaxLifetimeMs = plugin.getConfig().getLong("leaderboards.pool.max-lifetime-ms", 55000L);
        long maxLifetimeMs = configuredMaxLifetimeMs <= 30000L ? 55000L : configuredMaxLifetimeMs;
        maxLifetimeMs = Math.max(30000L, maxLifetimeMs);
        long keepaliveMs = Math.max(0L, plugin.getConfig().getLong("leaderboards.pool.keepalive-ms", 0L));
        if (keepaliveMs > 0L) {
            keepaliveMs = Math.max(30000L, keepaliveMs);
            if (keepaliveMs >= maxLifetimeMs) {
                keepaliveMs = 0L;
            }
        }

        config.setIdleTimeout(idleTimeoutMs);
        config.setMaxLifetime(maxLifetimeMs);
        if (keepaliveMs > 0L) {
            config.setKeepaliveTime(keepaliveMs);
        } else {
            config.setKeepaliveTime(0L);
        }

        try {
            dataSource = new HikariDataSource(config);
            try (Connection connection = dataSource.getConnection()) {
                createSkinCacheTable(connection);
            }
            boolean recovered = !databaseHealthy;
            databaseHealthy = true;
            if (recovered) {
                plugin.getLogger().info("[Leaderboards] Database connection ready (" + databaseTarget + ").");
            }
            if (!poolConfigLogged) {
                plugin.getLogger().info("Leaderboard pool tuning: maxPool=" + maxPoolSize
                        + ", minIdle=" + minIdle
                        + ", idleTimeoutMs=" + idleTimeoutMs
                        + ", maxLifetimeMs=" + maxLifetimeMs
                        + ", keepaliveMs=" + keepaliveMs + '.');
                poolConfigLogged = true;
            }
            return true;
        } catch (SQLException exception) {
            logDatabaseFailure("initialization", exception);
            close();
            return false;
        }
    }

    public boolean reload() {
        return initialize();
    }

    public String getPlayersTable() {
        return playersTable;
    }

    public String getPlayerBountiesTable() {
        return playerBountiesTable;
    }

    public String getKingdomsTable() {
        return kingdomsTable;
    }

    public String getKingdomMembersTable() {
        return kingdomMembersTable;
    }

    public String getKingdomBountiesTable() {
        return kingdomBountiesTable;
    }

    public Connection getConnection() throws SQLException {
        HikariDataSource activeSource = dataSource;
        if (activeSource == null || activeSource.isClosed()) {
            if (!recoverPool("pool was not initialized")) {
                throw new SQLException("Leaderboard database pool is not initialized.");
            }
            activeSource = dataSource;
        }

        try {
            return activeSource.getConnection();
        } catch (SQLException exception) {
            if (isRecoverableConnectionFailure(exception) && recoverPool(exception.getMessage())) {
                HikariDataSource recoveredSource = dataSource;
                if (recoveredSource != null && !recoveredSource.isClosed()) {
                    return recoveredSource.getConnection();
                }
            }

            logDatabaseFailure("connection acquisition", exception);
            throw exception;
        }
    }

    public boolean resetAllData() {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS player_skin_cache");
            createSkinCacheTable(connection);
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to reset leaderboard database tables.", exception);
            return false;
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private synchronized boolean recoverPool(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastRecoveryAttemptAt < RECOVERY_BACKOFF_MS) {
            return false;
        }
        lastRecoveryAttemptAt = now;

        if (now - lastFailureLogAt >= FAILURE_LOG_COOLDOWN_MS) {
            plugin.getLogger().warning("[Leaderboards] Database unavailable; retrying pool initialization (" + reason + ").");
            lastFailureLogAt = now;
        }

        return initialize();
    }

    private void buildTableNames() {
        playersTable = sanitizePrefix(plugin.getConfig().getString("bounty-database.table-prefix", "")) + "players";
        playerBountiesTable = sanitizePrefix(plugin.getConfig().getString("bounty-database.table-prefix", "")) + "player_bounties";
        kingdomBountiesTable = sanitizePrefix(plugin.getConfig().getString("bounty-database.table-prefix", "")) + "kingdom_bounties";
        kingdomsTable = sanitizePrefix(plugin.getConfig().getString("kingdoms-database.table-prefix", "")) + "kingdoms";
        kingdomMembersTable = sanitizePrefix(plugin.getConfig().getString("kingdoms-database.table-prefix", "")) + "kingdom_members";
    }

    private String sanitizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }

        String trimmed = prefix.trim();
        if (!trimmed.matches("[A-Za-z0-9_]*")) {
            plugin.getLogger().warning("Ignoring invalid table prefix '" + prefix + "'. Only letters, numbers, and underscore are allowed.");
            return "";
        }

        return trimmed;
    }

    private void createSkinCacheTable(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                "CREATE TABLE IF NOT EXISTS player_skin_cache (" +
                "  uuid CHAR(36) NOT NULL," +
                "  player_name VARCHAR(16) NOT NULL," +
                "  skin_url TEXT NOT NULL," +
                "  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (uuid)," +
                "  INDEX idx_skin_cache_name (player_name)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not create player_skin_cache table: " + exception.getMessage());
        }
    }

    public void upsertPlayerSkin(UUID uuid, String playerName, String skinUrl) {
        if (uuid == null || playerName == null || playerName.isBlank() || skinUrl == null || skinUrl.isBlank()) {
            return;
        }
                String sql = "INSERT INTO player_skin_cache (uuid, player_name, skin_url) VALUES (?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), skin_url = VALUES(skin_url), updated_at = CURRENT_TIMESTAMP";
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.setString(3, skinUrl);
            ps.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to upsert player skin cache for " + playerName + ": " + exception.getMessage());
        }
    }

    public Optional<String> findSkinUrl(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }

        try {
            UUID uuid = UUID.fromString(playerName.trim());
            try (Connection connection = getConnection();
                 PreparedStatement ps = connection.prepareStatement(
                         "SELECT skin_url FROM player_skin_cache WHERE uuid = ? LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String url = rs.getString("skin_url");
                        return url != null && !url.isBlank() ? Optional.of(url) : Optional.empty();
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Input is a player name, not a UUID.
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to query player skin cache by UUID for " + playerName + ": "
                    + exception.getMessage());
        }

        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(
                 "SELECT skin_url FROM player_skin_cache WHERE LOWER(player_name) = LOWER(?) LIMIT 1")) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String url = rs.getString("skin_url");
                    return url != null && !url.isBlank() ? Optional.of(url) : Optional.empty();
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to query player skin cache for " + playerName + ": " + exception.getMessage());
        }
        return Optional.empty();
    }

    private void logDatabaseFailure(String action, Throwable throwable) {
        databaseHealthy = false;

        long now = System.currentTimeMillis();
        if (now - lastFailureLogAt < FAILURE_LOG_COOLDOWN_MS) {
            return;
        }
        lastFailureLogAt = now;

        plugin.getLogger().warning("[Leaderboards] Database unavailable during " + action + " ("
                + databaseTarget + "): " + summarizeThrowable(throwable) + ". Retrying automatically.");
    }

    private boolean isRecoverableConnectionFailure(SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) {
            return true;
        }

        String message = exception.getMessage();
        if (message == null) {
            return false;
        }

        String normalized = message.toLowerCase();
        return normalized.contains("connection is not available")
                || normalized.contains("communications link failure")
                || normalized.contains("no operations allowed after connection closed")
                || normalized.contains("pool is not initialized");
    }

    private String summarizeThrowable(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }

        return root.getClass().getSimpleName() + ": " + message;
    }
}
