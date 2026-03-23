package com.chisellives;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PurchaseChecker {

    private static final long FAILURE_LOG_COOLDOWN_MS = 30000L;

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final LivesManager livesManager;

    private final AtomicBoolean polling = new AtomicBoolean(false);

    private BukkitTask task;
    private long lastFailureLogAt;
    private boolean pollingHealthy = true;

    public PurchaseChecker(JavaPlugin plugin, DatabaseManager databaseManager, LivesManager livesManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.livesManager = livesManager;
    }

    public void start() {
        stop();

        long intervalTicks = livesManager.getPurchaseCheckSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollPurchases, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void pollPurchases() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }

        databaseManager.getPendingPurchases(200)
                .thenCompose(this::processPurchases)
                .thenRun(this::logRecoveryIfNeeded)
                .exceptionally(throwable -> {
                    logPollingFailure(throwable);
                    return null;
                })
                .whenComplete((ignored, throwable) -> polling.set(false));
    }

    private CompletableFuture<Void> processPurchases(List<DatabaseManager.PurchaseRecord> purchases) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (DatabaseManager.PurchaseRecord purchase : purchases) {
            chain = chain.thenCompose(ignored -> processSinglePurchase(purchase));
        }

        return chain;
    }

    private CompletableFuture<Void> processSinglePurchase(DatabaseManager.PurchaseRecord purchase) {
        plugin.getLogger().info("[ChiselLives] Purchase detected: id=" + purchase.id() + ", type=" + purchase.type() + ", uuid=" + purchase.uuid());

        return livesManager.applyPurchase(purchase)
                .handle((outcome, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("[ChiselLives] Failed to process purchase " + purchase.id()
                                + ": " + summarizeThrowable(throwable));
                        return "failed";
                    }
                    return outcome.purchaseStatus();
                })
                .thenCompose(status -> databaseManager.updatePurchaseStatus(purchase.id(), status));
    }

    private void logPollingFailure(Throwable throwable) {
        pollingHealthy = false;

        long now = System.currentTimeMillis();
        if (now - lastFailureLogAt < FAILURE_LOG_COOLDOWN_MS) {
            return;
        }
        lastFailureLogAt = now;

        plugin.getLogger().warning("[ChiselLives] Purchase polling unavailable: "
                + summarizeThrowable(throwable) + ". Retrying automatically.");
    }

    private void logRecoveryIfNeeded() {
        if (!pollingHealthy) {
            pollingHealthy = true;
            plugin.getLogger().info("[ChiselLives] Purchase polling recovered.");
        }
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
