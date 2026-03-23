package com.chisellives;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.DiscordWebhookService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class LivesManager {

    private static final String OUT_OF_LIVES_REASON = "You ran out of lives.";

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final BanManager banManager;

    private final int startingLives;
    private final int maxNormalLives;
    private final int maxLives;
    private final int revivalLives;
    private final int purchaseCheckSeconds;
    private final String storeUrl;
    private Supplier<ItemStack> revivalTotemSupplier;

    public LivesManager(JavaPlugin plugin, DatabaseManager databaseManager, BanManager banManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.banManager = banManager;

        int configuredMaxLives = Math.max(plugin.getConfig().getInt("chisel-lives.max-lives", 15), 1);
        this.maxNormalLives = clamp(plugin.getConfig().getInt("chisel-lives.max-normal-lives", 10), 1, configuredMaxLives);
        this.maxLives = configuredMaxLives;
        this.startingLives = clamp(plugin.getConfig().getInt("chisel-lives.starting-lives", 10), 1, maxNormalLives);
        this.revivalLives = clamp(plugin.getConfig().getInt("chisel-lives.revival-lives", 5), 1, maxLives);
        this.purchaseCheckSeconds = Math.max(plugin.getConfig().getInt("chisel-lives.purchase-check-seconds", 10), 1);
        this.storeUrl = plugin.getConfig().getString("chisel-lives.store-url", "https://yourstorelink.com");
    }

    public int getPurchaseCheckSeconds() {
        return purchaseCheckSeconds;
    }

    public int getRevivalLives() {
        return revivalLives;
    }

    public String getStoreUrl() {
        return storeUrl;
    }

    public int getStartingLives() {
        return startingLives;
    }

    public int getMaxLives() {
        return maxLives;
    }

    public void setRevivalTotemSupplier(Supplier<ItemStack> revivalTotemSupplier) {
        this.revivalTotemSupplier = revivalTotemSupplier;
    }

    public CompletableFuture<DatabaseManager.PlayerRecord> addLivesAdmin(UUID uuid, String username, int amount) {
        int safeAmount = Math.max(amount, 0);
        return databaseManager.addLives(uuid, username, safeAmount, startingLives, maxLives)
                .thenApply(record -> {
                    String resolvedName = resolveUsername(record.username(), username);
                    if (record.lives() <= 0) {
                        banManager.banPlayer(uuid, resolvedName, OUT_OF_LIVES_REASON);
                    } else {
                        banManager.unbanPlayer(uuid, resolvedName);
                    }
                    return record;
                });
    }

    public CompletableFuture<DatabaseManager.PlayerRecord> setLivesAdmin(UUID uuid, String username, int lives) {
        int safeLives = clamp(lives, 0, maxLives);
        return databaseManager.setLives(uuid, username, safeLives)
                .thenApply(record -> {
                    String resolvedName = resolveUsername(record.username(), username);
                    if (record.lives() <= 0) {
                        banManager.banPlayer(uuid, resolvedName, OUT_OF_LIVES_REASON);
                    } else {
                        banManager.unbanPlayer(uuid, resolvedName);
                    }
                    return record;
                });
    }

    public CompletableFuture<DatabaseManager.PlayerRecord> getPlayerRecord(UUID uuid, String username) {
        return databaseManager.ensurePlayer(uuid, username, startingLives);
    }

    public CompletableFuture<Integer> getLives(UUID uuid, String username) {
        return databaseManager.getLives(uuid, username, startingLives);
    }

    public void handlePlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();

        databaseManager.ensurePlayer(uuid, username, startingLives)
                .thenAccept(record -> runSync(() -> {
                    Player onlinePlayer = Bukkit.getPlayer(uuid);
                    if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                        return;
                    }

                    if (record.banned() || record.lives() <= 0) {
                        banManager.banPlayer(uuid, resolveUsername(record.username(), username), OUT_OF_LIVES_REASON);
                    }
                    deliverPendingRevivalTotems(onlinePlayer);
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.SEVERE, "[ChiselLives] Failed to sync player data on join.", throwable);
                    return null;
                });
    }

    public void handlePlayerDeath(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();

        databaseManager.decrementLife(uuid, username, startingLives)
                .thenAccept(record -> runSync(() -> {
                    Player onlinePlayer = Bukkit.getPlayer(uuid);
                    if (onlinePlayer != null && onlinePlayer.isOnline()) {
                        onlinePlayer.sendMessage("§f[§cChiselLives§f] §7You fell in battle. §fLives remaining: §e" + record.lives() + "§f.");
                    }

                    if (record.lives() <= 0 || record.banned()) {
                        String resolvedName = resolveUsername(record.username(), username);
                        banManager.banPlayer(uuid, resolvedName, OUT_OF_LIVES_REASON);
                        Bukkit.broadcast(Component.text("§f[§cChiselLives§f] §e" + resolvedName + " §fran out of lives and was eliminated."));
                    }
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.SEVERE, "[ChiselLives] Failed to process player death.", throwable);
                    return null;
                });
    }

    public CompletableFuture<PurchaseOutcome> applyPurchase(DatabaseManager.PurchaseRecord purchase) {
        PurchaseType purchaseType = PurchaseType.fromRaw(purchase.type());
        if (purchaseType == PurchaseType.UNKNOWN) {
            return CompletableFuture.completedFuture(PurchaseOutcome.cancelled());
        }

        if (purchaseType == PurchaseType.REVIVAL) {
            return databaseManager.reviveBannedPlayer(purchase.uuid(), revivalLives)
                    .thenApply(reviveResult -> {
                        if (reviveResult.status() == DatabaseManager.ReviveStatus.NOT_FOUND) {
                            plugin.getLogger().warning("[ChiselLives] Revival purchase ignored: player does not exist for uuid " + purchase.uuid());
                            return PurchaseOutcome.cancelled();
                        }

                        if (reviveResult.status() == DatabaseManager.ReviveStatus.STILL_ALIVE) {
                            plugin.getLogger().warning("[ChiselLives] Revival purchase ignored: player is still alive for uuid " + purchase.uuid());
                            return PurchaseOutcome.cancelled();
                        }

                        DatabaseManager.PlayerRecord playerRecord = reviveResult.record();
                        runSync(() -> {
                            String displayName = resolveUsername(playerRecord.username(), purchase.uuid().toString());
                            banManager.unbanPlayer(purchase.uuid(), playerRecord.username());
                            Player onlinePlayer = Bukkit.getPlayer(purchase.uuid());
                            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                                onlinePlayer.sendMessage("You have been revived and now have " + playerRecord.lives() + " lives.");
                            }

                            Bukkit.broadcast(Component.text("[ChiselLives] " + displayName + " has been revived by a website purchase."));
                            sendRevivalWebhook(displayName, "Website Purchase", "Revived with " + playerRecord.lives() + " lives.");
                        });
                        plugin.getLogger().info("[ChiselLives] Player revived");
                        return PurchaseOutcome.processed();
                    });
        }

        if (purchaseType == PurchaseType.REVIVAL_TOTEM) {
            return databaseManager.addRevivalTotems(purchase.uuid(), purchase.uuid().toString(), 1, startingLives)
                    .thenApply(added -> {
                        String displayName = resolvePlayerName(purchase.uuid(), purchase.uuid().toString());
                        runSync(() -> {
                            Player onlinePlayer = Bukkit.getPlayer(purchase.uuid());
                            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                                deliverPendingRevivalTotems(onlinePlayer);
                            }
                            Bukkit.broadcast(Component.text("[ChiselLives] " + displayName + " bought a Revival Totem from the store."));
                            sendStoreWebhook(displayName, "Revival Totem", "Delivered 1 Revival Totem.");
                        });
                        return PurchaseOutcome.processed();
                    });
        }

        return databaseManager.applyLivesPurchase(
                        purchase.uuid(),
                        purchaseType.livesToAdd,
                        startingLives,
                        maxLives
                )
                .thenApply(result -> {
                    if (result.alreadyAtMax()) {
                        runSync(() -> sendMaxLivesMessage(purchase.uuid()));
                        return PurchaseOutcome.cancelled();
                    }

                    runSync(() -> {
                        String displayName = resolveUsername(result.record().username(), purchase.uuid().toString());
                        Player onlinePlayer = Bukkit.getPlayer(purchase.uuid());
                        if (onlinePlayer != null && onlinePlayer.isOnline()) {
                            onlinePlayer.sendMessage("Store purchase applied. You now have " + result.record().lives() + " lives remaining.");
                        }
                        Bukkit.broadcast(Component.text("[ChiselLives] " + displayName + " bought " + describeLivesPurchase(purchaseType) + " from the store."));
                        sendStoreWebhook(displayName, describeLivesPurchase(purchaseType),
                                "Lives: " + result.previousLives() + " -> " + result.record().lives());
                    });

                    return PurchaseOutcome.processed();
                });
    }

    private void sendMaxLivesMessage(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        player.sendMessage("You already have the maximum number of lives.");
        player.sendMessage("If you want more perks like trails and cosmetics, check out our ranks.");
    }

    private String resolveUsername(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void runSync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }

        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private void deliverPendingRevivalTotems(Player player) {
        if (player == null || revivalTotemSupplier == null) {
            return;
        }

        databaseManager.claimRevivalTotems(player.getUniqueId())
                .thenAccept(amount -> runSync(() -> {
                    if (amount <= 0 || !player.isOnline()) {
                        return;
                    }
                    for (int index = 0; index < amount; index++) {
                        player.getInventory().addItem(revivalTotemSupplier.get());
                    }
                    player.sendMessage("Store purchase applied. You received " + amount + " Revival Totem(s).");
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.SEVERE, "[ChiselLives] Failed to deliver revival totems.", throwable);
                    return null;
                });
    }

    private String describeLivesPurchase(PurchaseType purchaseType) {
        return switch (purchaseType) {
            case LIFE_1 -> "1 Life";
            case LIFE_5 -> "5 Lives";
            case LIFE_10 -> "10 Lives";
            case REVIVAL -> "Revival";
            case REVIVAL_TOTEM -> "Revival Totem";
            case UNKNOWN -> "Store Purchase";
        };
    }

    private String resolvePlayerName(UUID uuid, String fallback) {
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null) {
            return onlinePlayer.getName();
        }
        return fallback;
    }

    private void sendStoreWebhook(String playerName, String product, String details) {
        DiscordWebhookService discord = discord();
        if (discord != null) {
            discord.sendStorePurchase(playerName, product, details);
        }
    }

    private void sendRevivalWebhook(String revivedName, String source, String details) {
        DiscordWebhookService discord = discord();
        if (discord != null) {
            discord.sendRevival(revivedName, source, details);
        }
    }

    private DiscordWebhookService discord() {
        if (plugin instanceof ChiselRanksPlugin chiselRanksPlugin) {
            return chiselRanksPlugin.getDiscordWebhookService();
        }
        return null;
    }

    public record PurchaseOutcome(String purchaseStatus) {
        public static PurchaseOutcome processed() {
            return new PurchaseOutcome("processed");
        }

        public static PurchaseOutcome cancelled() {
            return new PurchaseOutcome("cancelled");
        }
    }

    private enum PurchaseType {
        LIFE_1(1),
        LIFE_5(5),
        LIFE_10(10),
        REVIVAL(0),
        REVIVAL_TOTEM(0),
        UNKNOWN(0);

        private final int livesToAdd;

        PurchaseType(int livesToAdd) {
            this.livesToAdd = livesToAdd;
        }

        private static PurchaseType fromRaw(String rawType) {
            if (rawType == null || rawType.isBlank()) {
                return UNKNOWN;
            }

            String normalized = rawType.trim()
                    .toLowerCase(Locale.ROOT)
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .replaceAll("\\s+", " ");

            return switch (normalized) {
                case "1", "1 life", "1 lives", "1life" -> LIFE_1;
                case "5", "5 life", "5 lives", "5lives" -> LIFE_5;
                case "10", "10 life", "10 lives", "10lives" -> LIFE_10;
                case "revival", "revive" -> REVIVAL;
                case "revival_totem", "totem", "revival totem" -> REVIVAL_TOTEM;
                default -> UNKNOWN;
            };
        }
    }
}
