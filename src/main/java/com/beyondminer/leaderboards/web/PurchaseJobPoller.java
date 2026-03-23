package com.beyondminer.leaderboards.web;

import com.beyondminer.bounty.database.DatabaseManager;
import com.beyondminer.leaderboards.BountyLeaderboards;
import com.chisellives.ChiselLives;
import com.chisellives.LivesManager;
import net.kyori.adventure.text.Component;
import com.chiselranks.RankManager;
import com.chiselranks.rank.Rank;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls the website_purchase_jobs table every 5 seconds and applies pending
 * store purchases (lives and ranks) that the website inserted directly into the
 * shared MySQL database.  This replaces the HTTP relay approach and requires no
 * inbound HTTP port on the game host.
 */
public final class PurchaseJobPoller {

    private static final long FAILURE_LOG_COOLDOWN_MS = 30000L;

    private final BountyLeaderboards plugin;
    private final DatabaseManager bountyDb;
    private final ChiselLives chiselLives;
    private BukkitTask task;
    private long lastFailureLogAt;
    private boolean pollHealthy = true;

    public PurchaseJobPoller(BountyLeaderboards plugin, DatabaseManager bountyDb, ChiselLives chiselLives) {
        this.plugin = plugin;
        this.bountyDb = bountyDb;
        this.chiselLives = chiselLives;
    }

    public void start() {
        createTableIfAbsent();
        // 100 ticks = 5 seconds
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollJobs, 100L, 100L);
        plugin.getLogger().info("[PurchasePoller] Started. Polling website_purchase_jobs every 5s.");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // -------------------------------------------------------------------------
    // Table setup
    // -------------------------------------------------------------------------

    private void createTableIfAbsent() {
        Connection conn = bountyDb.getConnection();
        if (conn == null) {
            plugin.getLogger().warning("[PurchasePoller] Cannot create table – no DB connection.");
            return;
        }

        String sql = "CREATE TABLE IF NOT EXISTS website_purchase_jobs ("
                + "id INT AUTO_INCREMENT PRIMARY KEY,"
                + "uuid VARCHAR(36) NOT NULL,"
                + "username VARCHAR(16),"
                + "product VARCHAR(32) NOT NULL,"
                + "status VARCHAR(16) DEFAULT 'pending',"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "processed_at TIMESTAMP NULL,"
                + "error_message VARCHAR(255) NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            plugin.getLogger().warning("[PurchasePoller] Failed to create website_purchase_jobs: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Poll loop
    // -------------------------------------------------------------------------

    private void pollJobs() {
        Connection conn = bountyDb.getConnection();
        if (conn == null) {
            logPollFailure("No DB connection.");
            return;
        }

        List<JobRow> pending = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, uuid, username, product FROM website_purchase_jobs"
                        + " WHERE status = 'pending' ORDER BY created_at ASC LIMIT 10");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                pending.add(new JobRow(
                        rs.getInt("id"),
                        rs.getString("uuid"),
                        rs.getString("username"),
                        rs.getString("product")
                ));
            }
            logRecoveryIfNeeded();
        } catch (SQLException e) {
            logPollFailure("Failed to fetch pending jobs: " + e.getMessage());
            return;
        }

        for (JobRow job : pending) {
            processJob(job);
        }
    }

    // -------------------------------------------------------------------------
    // Job processing
    // -------------------------------------------------------------------------

    private void processJob(JobRow job) {
        UUID uuid;
        try {
            uuid = UUID.fromString(job.uuid());
        } catch (IllegalArgumentException e) {
            failJob(job.id(), "Invalid UUID: " + job.uuid());
            return;
        }

        String product = job.product().trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');

        Rank rank = resolveRank(product);

        try {
            if (rank != null) {
                applyRankJob(job, uuid, rank);
            } else {
                applyLivesJob(job, uuid, product);
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            failJob(job.id(), msg);
        }
    }

    private void applyRankJob(JobRow job, UUID uuid, Rank rank) throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                plugin.getRankManager().grantRank(uuid, rank, RankManager.GrantAction.GRANT);
                future.complete(null);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        });

        future.get(10, TimeUnit.SECONDS);
        markProcessed(job.id());
        announceRankPurchase(job.username(), uuid, rank);
        plugin.getLogger().info("[PurchasePoller] Rank " + rank.getKey()
                + " applied to " + uuid + " (job #" + job.id() + ")");
    }

    private void applyLivesJob(JobRow job, UUID uuid, String product) throws Exception {
        if (chiselLives == null) {
            failJob(job.id(), "ChiselLives unavailable");
            return;
        }

        LivesManager lm = chiselLives.getLivesManager();
        if (lm == null) {
            failJob(job.id(), "LivesManager unavailable");
            return;
        }

        String livesType = mapProductToLivesType(product);
        if (livesType == null) {
            failJob(job.id(), "Unknown product: " + product);
            return;
        }

        com.chisellives.DatabaseManager.PurchaseRecord record =
                new com.chisellives.DatabaseManager.PurchaseRecord(-1L, uuid, livesType);

        LivesManager.PurchaseOutcome outcome = lm.applyPurchase(record).get(10, TimeUnit.SECONDS);

        if (!"processed".equalsIgnoreCase(outcome.purchaseStatus())) {
            failJob(job.id(), "Purchase rejected by LivesManager: " + outcome.purchaseStatus());
            return;
        }

        markProcessed(job.id());
        plugin.getLogger().info("[PurchasePoller] Lives (" + livesType + ") applied to "
                + uuid + " (job #" + job.id() + ")");
    }

    // -------------------------------------------------------------------------
    // Status updates
    // -------------------------------------------------------------------------

    private void markProcessed(int jobId) {
        Connection conn = bountyDb.getConnection();
        if (conn == null) return;

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE website_purchase_jobs SET status = 'processed', processed_at = NOW() WHERE id = ?")) {
            ps.setInt(1, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[PurchasePoller] Failed to mark job #" + jobId + " processed: " + e.getMessage());
        }
    }

    private void failJob(int jobId, String message) {
        plugin.getLogger().warning("[PurchasePoller] Job #" + jobId + " failed: " + message);

        Connection conn = bountyDb.getConnection();
        if (conn == null) return;

        String truncated = message != null && message.length() > 250 ? message.substring(0, 250) : message;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE website_purchase_jobs SET status = 'failed', processed_at = NOW(),"
                        + " error_message = ? WHERE id = ?")) {
            ps.setString(1, truncated);
            ps.setInt(2, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[PurchasePoller] Failed to mark job #" + jobId + " failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Product resolution
    // -------------------------------------------------------------------------

    private Rank resolveRank(String product) {
        return switch (product) {
            case "rank_gold", "gold" -> Rank.GOLD;
            case "rank_diamond", "diamond" -> Rank.DIAMOND;
            case "rank_netherite", "netherite" -> Rank.NETHERITE;
            default -> null;
        };
    }

    private String mapProductToLivesType(String product) {
        return switch (product) {
            case "1_life", "1_lives", "life_1", "1", "1life" -> "1 life";
            case "5_lives", "5_life", "life_5", "5", "5lives" -> "5 lives";
            case "10_lives", "10_life", "life_10", "10", "10lives" -> "10 lives";
            case "revival", "revive" -> "revival";
            case "revival_totem", "totem" -> "revival_totem";
            default -> null;
        };
    }

    private void announceRankPurchase(String username, UUID uuid, Rank rank) {
        String displayName = username != null && !username.isBlank() ? username : uuid.toString();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.broadcast(Component.text("[ChiselRanks] " + displayName + " bought the " + rank.getDisplayName() + " rank."));
            if (plugin.getDiscordWebhookService() != null) {
                plugin.getDiscordWebhookService().sendStorePurchase(displayName, rank.getDisplayName() + " Rank",
                        "Rank purchase processed by website job poller.");
            }
        });
    }

    private void logPollFailure(String message) {
        pollHealthy = false;

        long now = System.currentTimeMillis();
        if (now - lastFailureLogAt < FAILURE_LOG_COOLDOWN_MS) {
            return;
        }
        lastFailureLogAt = now;
        plugin.getLogger().warning("[PurchasePoller] Website purchase polling unavailable: " + message + " Retrying automatically.");
    }

    private void logRecoveryIfNeeded() {
        if (!pollHealthy) {
            pollHealthy = true;
            plugin.getLogger().info("[PurchasePoller] Website purchase polling recovered.");
        }
    }

    // -------------------------------------------------------------------------

    private record JobRow(int id, String uuid, String username, String product) {}
}
