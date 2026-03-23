package com.chisellives;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public final class DatabaseManager {

    private static final long RECOVERY_BACKOFF_MS = 10000L;
    private static final long FAILURE_LOG_COOLDOWN_MS = 30000L;

    private final JavaPlugin plugin;
    private final ExecutorService executor;

    private HikariDataSource dataSource;
    private String playersTable;
    private String purchasesTable;
    private String totemsTable;
    private String databaseTarget;
    private long lastRecoveryLogAt;
    private long lastRecoveryAttemptAt;
    private long lastFailureLogAt;
    private boolean databaseHealthy;
    private boolean poolConfigLogged;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ChiselLives-DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized boolean initialize() {
        closeDataSourceQuietly();

        String host = plugin.getConfig().getString("database.host", "localhost");
        int port = plugin.getConfig().getInt("database.port", 3306);
        String databaseName = plugin.getConfig().getString("database.name");
        if (databaseName == null || databaseName.isBlank()) {
            databaseName = plugin.getConfig().getString("database.database", "minecraft");
        }
        String username = plugin.getConfig().getString("database.username", "root");
        String password = plugin.getConfig().getString("database.password", "");
        databaseTarget = host + ":" + port + "/" + databaseName;

        playersTable = sanitizeTableName(plugin.getConfig().getString("chisel-lives.players-table", "players"), "players");
        purchasesTable = sanitizeTableName(plugin.getConfig().getString("chisel-lives.purchases-table", "purchases"), "purchases");
        totemsTable = sanitizeTableName(plugin.getConfig().getString("chisel-lives.totems-table", "chisel_lives_totems"), "chisel_lives_totems");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("ChiselLivesPool");
        hikariConfig.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&tcpKeepAlive=true&connectTimeout=10000&socketTimeout=30000",
                host,
                port,
                databaseName
        ));
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        int maxPoolSize = Math.max(2, plugin.getConfig().getInt("chisel-lives.pool.max-size", 4));
        int minIdle = Math.max(0, plugin.getConfig().getInt("chisel-lives.pool.min-idle", 0));
        if (minIdle >= maxPoolSize) {
            minIdle = Math.max(0, maxPoolSize - 1);
        }

        long connectionTimeoutMs = Math.max(10000L, plugin.getConfig().getLong("chisel-lives.pool.connection-timeout-ms", 15000L));
        long validationTimeoutMs = Math.max(3000L, plugin.getConfig().getLong("chisel-lives.pool.validation-timeout-ms", 5000L));
        long idleTimeoutMs = Math.max(10000L, plugin.getConfig().getLong("chisel-lives.pool.idle-timeout-ms", 15000L));
        long configuredMaxLifetimeMs = plugin.getConfig().getLong("chisel-lives.pool.max-lifetime-ms", 55000L);
        long maxLifetimeMs = configuredMaxLifetimeMs <= 30000L ? 55000L : configuredMaxLifetimeMs;
        maxLifetimeMs = Math.max(30000L, maxLifetimeMs);
        long keepaliveMs = Math.max(0L, plugin.getConfig().getLong("chisel-lives.pool.keepalive-ms", 0L));
        if (keepaliveMs > 0L) {
            keepaliveMs = Math.max(30000L, keepaliveMs);
            if (keepaliveMs >= maxLifetimeMs) {
                keepaliveMs = 0L;
            }
        }

        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setMinimumIdle(minIdle);
        hikariConfig.setConnectionTimeout(connectionTimeoutMs);
        hikariConfig.setValidationTimeout(validationTimeoutMs);
        hikariConfig.setIdleTimeout(idleTimeoutMs);
        hikariConfig.setMaxLifetime(maxLifetimeMs);
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.addDataSourceProperty("tcpKeepAlive", "true");
        if (keepaliveMs > 0L) {
            hikariConfig.setKeepaliveTime(keepaliveMs);
        } else {
            hikariConfig.setKeepaliveTime(0L);
        }

        try {
            dataSource = new HikariDataSource(hikariConfig);
            try (Connection connection = dataSource.getConnection()) {
            playersTable = resolveCompatibleTable(
                connection,
                playersTable,
                "chisel_lives_players",
                Set.of("uuid", "username", "lives", "banned")
            );
            purchasesTable = resolveCompatibleTable(
                connection,
                purchasesTable,
                "chisel_lives_purchases",
                Set.of("id", "uuid", "type", "status", "purchase_date")
            );
                createTables(connection);
            }
            boolean recovered = !databaseHealthy;
            databaseHealthy = true;
            if (recovered) {
                plugin.getLogger().info("[ChiselLives] Database connection ready (" + databaseTarget + ").");
            }
            if (!poolConfigLogged) {
                plugin.getLogger().info("[ChiselLives] Pool tuning: maxPool=" + maxPoolSize
                        + ", minIdle=" + minIdle
                        + ", idleTimeoutMs=" + idleTimeoutMs
                        + ", maxLifetimeMs=" + maxLifetimeMs
                        + ", keepaliveMs=" + keepaliveMs + '.');
                poolConfigLogged = true;
            }
            return true;
        } catch (SQLException exception) {
            logDatabaseFailure("initialization", exception);
            closeDataSourceQuietly();
            return false;
        }
    }

    public synchronized void close() {
        executor.shutdownNow();
        closeDataSourceQuietly();
    }

    public synchronized boolean resetAllData() {
        if (dataSource == null || dataSource.isClosed()) {
            if (!initialize()) {
                return false;
            }
        }

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                statement.execute("DROP TABLE IF EXISTS " + purchasesTable);
                statement.execute("DROP TABLE IF EXISTS " + playersTable);
                statement.execute("DROP TABLE IF EXISTS " + totemsTable);
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }

            createTables(connection);
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "[ChiselLives] Failed to reset lives database tables.", exception);
            return false;
        }
    }

    public CompletableFuture<PlayerRecord> ensurePlayer(UUID uuid, String username, int startingLives) {
        return supplyAsync(() -> ensurePlayerSync(uuid, username, startingLives));
    }

    public CompletableFuture<Integer> getLives(UUID uuid, String username, int startingLives) {
        return ensurePlayer(uuid, username, startingLives).thenApply(PlayerRecord::lives);
    }

    public CompletableFuture<PlayerRecord> decrementLife(UUID uuid, String username, int startingLives) {
        return supplyAsync(() -> decrementLifeSync(uuid, username, startingLives));
    }

    public CompletableFuture<LifePurchaseResult> applyLivesPurchase(UUID uuid, int amount, int startingLives, int maxLives) {
        return supplyAsync(() -> applyLivesPurchaseSync(uuid, amount, startingLives, maxLives));
    }

    public CompletableFuture<PlayerRecord> addLives(UUID uuid, String username, int amount, int startingLives, int maxLives) {
        return supplyAsync(() -> addLivesSync(uuid, username, amount, startingLives, maxLives));
    }

    public CompletableFuture<PlayerRecord> setLives(UUID uuid, String username, int lives) {
        return supplyAsync(() -> setLivesSync(uuid, username, lives));
    }

    public CompletableFuture<PlayerRecord> findPlayer(UUID uuid) {
        return supplyAsync(() -> findPlayerSync(uuid));
    }

    public CompletableFuture<PlayerRecord> revivePlayer(UUID uuid, int revivalLives, int startingLives) {
        return supplyAsync(() -> revivePlayerSync(uuid, revivalLives, startingLives));
    }

    public CompletableFuture<ReviveResult> reviveBannedPlayer(UUID uuid, int revivalLives) {
        return supplyAsync(() -> reviveBannedPlayerSync(uuid, revivalLives));
    }

    public CompletableFuture<ReviveResult> reviveBannedPlayerByUsername(String username, int revivalLives) {
        return supplyAsync(() -> reviveBannedPlayerByUsernameSync(username, revivalLives));
    }

    public CompletableFuture<List<PurchaseRecord>> getPendingPurchases(int limit) {
        return supplyAsync(() -> getPendingPurchasesSync(limit));
    }

    public CompletableFuture<Integer> addRevivalTotems(UUID uuid, String username, int amount, int startingLives) {
        return supplyAsync(() -> addRevivalTotemsSync(uuid, username, amount, startingLives));
    }

    public CompletableFuture<Integer> claimRevivalTotems(UUID uuid) {
        return supplyAsync(() -> claimRevivalTotemsSync(uuid));
    }

    public CompletableFuture<Void> updatePurchaseStatus(long purchaseId, String status) {
        return runAsync(() -> updatePurchaseStatusSync(purchaseId, status));
    }

    private PlayerRecord ensurePlayerSync(UUID uuid, String username, int startingLives) throws SQLException {
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertPlayer(connection, uuid, username, startingLives);
                PlayerRecord record = loadPlayerForUpdate(connection, uuid);
                connection.commit();
                return record;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private PlayerRecord decrementLifeSync(UUID uuid, String username, int startingLives) throws SQLException {
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertPlayer(connection, uuid, username, startingLives);
                PlayerRecord current = loadPlayerForUpdate(connection, uuid);

                int updatedLives = Math.max(current.lives() - 1, 0);
                boolean banned = updatedLives <= 0;
                String resolvedUsername = resolveUsername(username, current.username());

                updatePlayer(connection, uuid, resolvedUsername, updatedLives, banned);
                connection.commit();

                return new PlayerRecord(uuid, resolvedUsername, updatedLives, banned);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private LifePurchaseResult applyLivesPurchaseSync(UUID uuid, int amount, int startingLives, int maxLives) throws SQLException {
        int safeAmount = Math.max(amount, 0);

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertPlayer(connection, uuid, "", startingLives);
                PlayerRecord current = loadPlayerForUpdate(connection, uuid);

                if (current.lives() >= maxLives) {
                    connection.commit();
                    return new LifePurchaseResult(current, current.lives(), true);
                }

                int updatedLives = Math.min(current.lives() + safeAmount, maxLives);
                updatePlayer(connection, uuid, current.username(), updatedLives, current.banned());
                connection.commit();

                PlayerRecord updated = new PlayerRecord(uuid, current.username(), updatedLives, current.banned());
                return new LifePurchaseResult(updated, current.lives(), false);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private PlayerRecord addLivesSync(UUID uuid, String username, int amount, int startingLives, int maxLives) throws SQLException {
        int safeAmount = Math.max(amount, 0);
        int safeMaxLives = Math.max(maxLives, 1);

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertPlayer(connection, uuid, username, startingLives);
                PlayerRecord current = loadPlayerForUpdate(connection, uuid);

                int updatedLives = Math.min(Math.max(current.lives() + safeAmount, 0), safeMaxLives);
                boolean banned = updatedLives <= 0;
                String resolvedUsername = resolveUsername(username, current.username());

                updatePlayer(connection, uuid, resolvedUsername, updatedLives, banned);
                connection.commit();

                return new PlayerRecord(uuid, resolvedUsername, updatedLives, banned);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private PlayerRecord setLivesSync(UUID uuid, String username, int lives) throws SQLException {
        int safeLives = Math.max(lives, 0);

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertPlayer(connection, uuid, username, safeLives);
                PlayerRecord current = loadPlayerForUpdate(connection, uuid);

                boolean banned = safeLives <= 0;
                String resolvedUsername = resolveUsername(username, current.username());

                updatePlayer(connection, uuid, resolvedUsername, safeLives, banned);
                connection.commit();

                return new PlayerRecord(uuid, resolvedUsername, safeLives, banned);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private PlayerRecord findPlayerSync(UUID uuid) throws SQLException {
        String query = "SELECT uuid, username, lives, banned FROM " + playersTable + " WHERE uuid = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String username = resultSet.getString("username");
                    int lives = resultSet.getInt("lives");
                    boolean banned = resultSet.getBoolean("banned");
                    return new PlayerRecord(uuid, username == null ? "" : username, lives, banned);
                }
            }
        }

        return null;
    }

    private PlayerRecord revivePlayerSync(UUID uuid, int revivalLives, int startingLives) throws SQLException {
        int safeRevivalLives = Math.max(revivalLives, 1);

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertPlayer(connection, uuid, "", startingLives);
                PlayerRecord current = loadPlayerForUpdate(connection, uuid);

                updatePlayer(connection, uuid, current.username(), safeRevivalLives, false);
                connection.commit();

                return new PlayerRecord(uuid, current.username(), safeRevivalLives, false);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private ReviveResult reviveBannedPlayerSync(UUID uuid, int revivalLives) throws SQLException {
        int safeRevivalLives = Math.max(revivalLives, 1);

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                PlayerRecord current = loadPlayerForUpdateIfExists(connection, uuid);
                if (current == null) {
                    connection.commit();
                    return ReviveResult.notFound();
                }

                if (!current.banned() || current.lives() > 0) {
                    connection.commit();
                    return ReviveResult.stillAlive(current);
                }

                updatePlayer(connection, uuid, current.username(), safeRevivalLives, false);
                connection.commit();

                return ReviveResult.revived(new PlayerRecord(uuid, current.username(), safeRevivalLives, false));
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private ReviveResult reviveBannedPlayerByUsernameSync(String username, int revivalLives) throws SQLException {
        if (username == null || username.isBlank()) {
            return ReviveResult.notFound();
        }

        int safeRevivalLives = Math.max(revivalLives, 1);

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                PlayerRecord current = loadPlayerForUpdateByUsername(connection, username.trim());
                if (current == null) {
                    connection.commit();
                    return ReviveResult.notFound();
                }

                if (!current.banned() || current.lives() > 0) {
                    connection.commit();
                    return ReviveResult.stillAlive(current);
                }

                updatePlayer(connection, current.uuid(), current.username(), safeRevivalLives, false);
                connection.commit();

                return ReviveResult.revived(new PlayerRecord(current.uuid(), current.username(), safeRevivalLives, false));
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private List<PurchaseRecord> getPendingPurchasesSync(int limit) throws SQLException {
        int safeLimit = Math.max(limit, 1);
        String query = "SELECT id, uuid, type FROM " + purchasesTable + " WHERE status = 'pending' ORDER BY id ASC LIMIT ?";

        List<PurchaseRecord> purchases = new ArrayList<>();
        List<Long> invalidPurchaseIds = new ArrayList<>();

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long purchaseId = resultSet.getLong("id");
                    String uuidText = resultSet.getString("uuid");
                    String type = resultSet.getString("type");

                    try {
                        UUID uuid = UUID.fromString(uuidText);
                        purchases.add(new PurchaseRecord(purchaseId, uuid, type));
                    } catch (IllegalArgumentException exception) {
                        invalidPurchaseIds.add(purchaseId);
                        plugin.getLogger().warning("[ChiselLives] Purchase " + purchaseId + " has invalid UUID '" + uuidText + "'. Marking as failed.");
                    }
                }
            }

            for (Long invalidId : invalidPurchaseIds) {
                updatePurchaseStatus(connection, invalidId, "failed");
            }
        }

        return purchases;
    }

    private int addRevivalTotemsSync(UUID uuid, String username, int amount, int startingLives) throws SQLException {
        int safeAmount = Math.max(1, amount);
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertPlayer(connection, uuid, username, startingLives);
                String sql = "INSERT INTO " + totemsTable + " (uuid, amount) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, uuid.toString());
                    statement.setInt(2, safeAmount);
                    statement.executeUpdate();
                }
                connection.commit();
                return safeAmount;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private int claimRevivalTotemsSync(UUID uuid) throws SQLException {
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                int amount = 0;
                try (PreparedStatement statement = connection.prepareStatement("SELECT amount FROM " + totemsTable + " WHERE uuid = ? FOR UPDATE")) {
                    statement.setString(1, uuid.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            amount = Math.max(0, resultSet.getInt("amount"));
                        }
                    }
                }

                if (amount > 0) {
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + totemsTable + " WHERE uuid = ?")) {
                        delete.setString(1, uuid.toString());
                        delete.executeUpdate();
                    }
                }
                connection.commit();
                return amount;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void updatePurchaseStatusSync(long purchaseId, String status) throws SQLException {
        try (Connection connection = getConnection()) {
            updatePurchaseStatus(connection, purchaseId, status);
        }
    }

    private void updatePurchaseStatus(Connection connection, long purchaseId, String status) throws SQLException {
        String update = "UPDATE " + purchasesTable + " SET status = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setString(1, status);
            statement.setLong(2, purchaseId);
            statement.executeUpdate();
        }
    }

    private void upsertPlayer(Connection connection, UUID uuid, String username, int startingLives) throws SQLException {
        String insert = "INSERT INTO " + playersTable + " (uuid, username, lives, banned) VALUES (?, ?, ?, FALSE) "
                + "ON DUPLICATE KEY UPDATE username = CASE WHEN VALUES(username) = '' THEN username ELSE VALUES(username) END";

        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, normalizeUsername(username));
            statement.setInt(3, startingLives);
            statement.executeUpdate();
        }
    }

    private PlayerRecord loadPlayerForUpdate(Connection connection, UUID uuid) throws SQLException {
        PlayerRecord existing = loadPlayerForUpdateIfExists(connection, uuid);
        if (existing != null) {
            return existing;
        }

        return new PlayerRecord(uuid, "", 10, false);
    }

    private PlayerRecord loadPlayerForUpdateIfExists(Connection connection, UUID uuid) throws SQLException {
        String query = "SELECT uuid, username, lives, banned FROM " + playersTable + " WHERE uuid = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String username = resultSet.getString("username");
                    int lives = resultSet.getInt("lives");
                    boolean banned = resultSet.getBoolean("banned");
                    return new PlayerRecord(uuid, username == null ? "" : username, lives, banned);
                }
            }
        }

        return null;
    }

    private PlayerRecord loadPlayerForUpdateByUsername(Connection connection, String username) throws SQLException {
        String query = "SELECT uuid, username, lives, banned FROM " + playersTable
                + " WHERE LOWER(username) = LOWER(?) LIMIT 1 FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                String uuidText = resultSet.getString("uuid");
                try {
                    UUID uuid = UUID.fromString(uuidText);
                    String storedName = resultSet.getString("username");
                    int lives = resultSet.getInt("lives");
                    boolean banned = resultSet.getBoolean("banned");
                    return new PlayerRecord(uuid, storedName == null ? "" : storedName, lives, banned);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
    }

    private void updatePlayer(Connection connection, UUID uuid, String username, int lives, boolean banned) throws SQLException {
        String update = "UPDATE " + playersTable + " SET username = ?, lives = ?, banned = ? WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setString(1, normalizeUsername(username));
            statement.setInt(2, lives);
            statement.setBoolean(3, banned);
            statement.setString(4, uuid.toString());
            statement.executeUpdate();
        }
    }

    private void createTables(Connection connection) throws SQLException {
        String playersSql = "CREATE TABLE IF NOT EXISTS " + playersTable + " ("
                + "uuid VARCHAR(36) PRIMARY KEY,"
                + "username VARCHAR(16),"
                + "lives INT DEFAULT 10,"
                + "banned BOOLEAN DEFAULT FALSE"
                + ")";

        String purchasesSql = "CREATE TABLE IF NOT EXISTS " + purchasesTable + " ("
                + "id INT AUTO_INCREMENT PRIMARY KEY,"
                + "uuid VARCHAR(36),"
                + "type VARCHAR(20),"
                + "status VARCHAR(20) DEFAULT 'pending',"
                + "purchase_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")";

        String totemsSql = "CREATE TABLE IF NOT EXISTS " + totemsTable + " ("
                + "uuid VARCHAR(36) PRIMARY KEY,"
                + "amount INT DEFAULT 0"
                + ")";

        try (Statement statement = connection.createStatement()) {
            statement.execute(playersSql);
            statement.execute(purchasesSql);
            statement.execute(totemsSql);
        }
    }

    private String resolveCompatibleTable(Connection connection, String configuredTable, String fallbackTable, Set<String> requiredColumns) throws SQLException {
        String safeConfiguredTable = sanitizeTableName(configuredTable, fallbackTable);
        if (!tableExists(connection, safeConfiguredTable)) {
            return safeConfiguredTable;
        }

        if (tableHasRequiredColumns(connection, safeConfiguredTable, requiredColumns)) {
            return safeConfiguredTable;
        }

        String safeFallbackTable = sanitizeTableName(fallbackTable, fallbackTable);
        plugin.getLogger().warning("[ChiselLives] Table '" + safeConfiguredTable
                + "' exists but does not match ChiselLives schema. Using '"
                + safeFallbackTable + "' instead.");
        return safeFallbackTable;
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        String query = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean tableHasRequiredColumns(Connection connection, String tableName, Set<String> requiredColumns) throws SQLException {
        String query = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        Set<String> existingColumns = new HashSet<>();

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String columnName = resultSet.getString("COLUMN_NAME");
                    if (columnName != null) {
                        existingColumns.add(columnName.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        for (String requiredColumn : requiredColumns) {
            if (!existingColumns.contains(requiredColumn.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        return true;
    }

    private Connection getConnection() throws SQLException {
        HikariDataSource activeSource = dataSource;
        if (activeSource == null || activeSource.isClosed()) {
            if (!recoverPool("pool was not initialized")) {
                throw new SQLException("ChiselLives database pool is not initialized.");
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
            throw exception;
        }
    }

    private void closeDataSourceQuietly() {
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
        if (now - lastRecoveryLogAt > FAILURE_LOG_COOLDOWN_MS) {
            plugin.getLogger().warning("[ChiselLives] Database unavailable; retrying pool initialization (" + reason + ").");
            lastRecoveryLogAt = now;
        }

        return initialize();
    }

    private boolean isRecoverableConnectionFailure(SQLException exception) {
        if (exception instanceof SQLTransientConnectionException) {
            return true;
        }

        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) {
            return true;
        }

        String message = exception.getMessage();
        if (message == null) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("connection is not available")
                || normalized.contains("communications link failure")
                || normalized.contains("no operations allowed after connection closed");
    }

    private String sanitizeTableName(String configured, String fallback) {
        if (configured == null || configured.isBlank()) {
            return fallback;
        }

        String trimmed = configured.trim();
        if (!trimmed.matches("[A-Za-z0-9_]+")) {
            plugin.getLogger().warning("[ChiselLives] Invalid table name '" + configured + "'. Using '" + fallback + "'.");
            return fallback;
        }

        return trimmed;
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return "";
        }

        String trimmed = username.trim();
        if (trimmed.length() <= 16) {
            return trimmed;
        }

        return trimmed.substring(0, 16);
    }

    private String resolveUsername(String preferred, String fallback) {
        String normalizedPreferred = normalizeUsername(preferred);
        if (!normalizedPreferred.isBlank()) {
            return normalizedPreferred;
        }

        return normalizeUsername(fallback);
    }

    private void logDatabaseFailure(String action, Throwable throwable) {
        databaseHealthy = false;

        long now = System.currentTimeMillis();
        if (now - lastFailureLogAt < FAILURE_LOG_COOLDOWN_MS) {
            return;
        }
        lastFailureLogAt = now;
        lastRecoveryLogAt = now;

        plugin.getLogger().warning("[ChiselLives] Database unavailable during " + action + " ("
                + databaseTarget + "): " + summarizeThrowable(throwable) + ". Retrying automatically.");
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

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        if (executor.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database executor is shut down."));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    private CompletableFuture<Void> runAsync(SqlRunnable runnable) {
        return supplyAsync(() -> {
            runnable.run();
            return null;
        });
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws Exception;
    }

    public record PlayerRecord(UUID uuid, String username, int lives, boolean banned) {
    }

    public record PurchaseRecord(long id, UUID uuid, String type) {
    }

    public record LifePurchaseResult(PlayerRecord record, int previousLives, boolean alreadyAtMax) {
    }

    public enum ReviveStatus {
        REVIVED,
        NOT_FOUND,
        STILL_ALIVE
    }

    public record ReviveResult(ReviveStatus status, PlayerRecord record) {
        public static ReviveResult revived(PlayerRecord record) {
            return new ReviveResult(ReviveStatus.REVIVED, record);
        }

        public static ReviveResult notFound() {
            return new ReviveResult(ReviveStatus.NOT_FOUND, null);
        }

        public static ReviveResult stillAlive(PlayerRecord record) {
            return new ReviveResult(ReviveStatus.STILL_ALIVE, record);
        }
    }
}
