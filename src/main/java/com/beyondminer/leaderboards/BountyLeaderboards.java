package com.beyondminer.leaderboards;

import com.beyondminer.bounty.commands.BountyCommand;
import com.beyondminer.bounty.listeners.PlayerKillListener;
import com.beyondminer.bounty.managers.BountyManager;
import com.beyondminer.bounty.managers.ContractManager;
import com.beyondminer.bounty.managers.KingdomIntegrationManager;
import com.beyondminer.npc.PlayerStatueManager;
import com.beyondminer.war.commands.WarCommand;
import com.beyondminer.war.listeners.WarKillListener;
import com.beyondminer.war.managers.WarManager;
import com.beyondminer.assassination.listeners.AssassinationListener;
import com.beyondminer.assassination.managers.AssassinationManager;
import com.beyondminer.assassination.models.AssassinationContract;
import com.beyondminer.kingdoms.commands.AllyCommand;
import com.beyondminer.kingdoms.commands.KingdomCommand;
import com.beyondminer.kingdoms.listeners.ChatListener;
import com.beyondminer.kingdoms.listeners.JoinListener;
import com.beyondminer.kingdoms.managers.AllyManager;
import com.beyondminer.kingdoms.managers.ChatManager;
import com.beyondminer.kingdoms.managers.InviteManager;
import com.beyondminer.kingdoms.managers.KingdomManager;
import com.beyondminer.leaderboards.commands.ChiselModCommand;
import com.beyondminer.leaderboards.commands.HelpCommand;
import com.beyondminer.leaderboards.commands.LeaderboardCommand;
import com.beyondminer.leaderboards.listeners.HelpListener;
import com.beyondminer.leaderboards.managers.DatabaseManager;
import com.beyondminer.leaderboards.managers.HologramManager;
import com.beyondminer.skin.SkinCacheListener;
import com.beyondminer.leaderboards.managers.LeaderboardManager;
import com.beyondminer.leaderboards.models.LeaderboardEntry;
import com.beyondminer.leaderboards.web.PurchaseJobPoller;
import com.beyondminer.leaderboards.web.WebSyncServer;
import com.chisellives.ChiselLives;
import com.chiselranks.ChiselRanksPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class BountyLeaderboards extends ChiselRanksPlugin {

    private static final int DEFAULT_REFRESH_SECONDS = 60;
    private static final long REFRESH_FAILURE_LOG_COOLDOWN_MS = 30000L;

    private com.beyondminer.bounty.database.DatabaseManager bountyDatabaseManager;
    private BountyManager bountyManager;
    private com.beyondminer.bounty.managers.LeaderboardManager bountyLeaderboardManager;
    private ContractManager contractManager;
    private KingdomIntegrationManager kingdomIntegrationManager;
    private WarManager warManager;
    private AssassinationManager assassinationManager;

    private com.beyondminer.kingdoms.database.DatabaseManager kingdomsDatabaseManager;
    private KingdomManager kingdomManager;
    private InviteManager inviteManager;
    private ChatManager chatManager;
    private AllyManager allyManager;

    private DatabaseManager databaseManager;
    private LeaderboardManager leaderboardManager;
    private HologramManager hologramManager;

    private Location playersLocation;
    private Location kingdomsLocation;
    private long refreshTicks;

    private BukkitTask refreshTask;
    private BukkitTask assassinationExpiryTask;
    private BukkitTask hologramRotationTask;
    private ChiselLives chiselLives;
    private PlayerStatueManager playerStatueManager;
    private WebSyncServer webSyncServer;
    private PurchaseJobPoller purchaseJobPoller;
    private File leaderboardLocationFile;
    private YamlConfiguration leaderboardLocationConfig;
    private List<Component> rotatingPlayerTitles = List.of(Component.text("Top Players Leaderboard", NamedTextColor.AQUA));
    private List<Component> rotatingKingdomTitles = List.of(Component.text("Top Kingdoms Leaderboard", NamedTextColor.AQUA));
    private int hologramRotationIndex;
    private long lastRefreshFailureLogAt;
    private boolean leaderboardRefreshHealthy = true;

    @Override
    public void onEnable() {
        super.onEnable();
        if (!isEnabled()) {
            return;
        }

        getLogger().info("Loading KingdomsBounty...");

        saveDefaultConfig();
        logConfiguredDatabaseModes();

        if (!initializeKingdomsSystem()) {
            getLogger().severe("Disabling KingdomsBounty because kingdoms database connection failed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!initializeBountySystem()) {
            getLogger().severe("Disabling KingdomsBounty because bounty database connection failed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Disabling KingdomsBounty because leaderboard database connection failed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        leaderboardManager = new LeaderboardManager(this, databaseManager);
        hologramManager = new HologramManager();
        leaderboardLocationFile = new File(getDataFolder(), "leaderboard-locations.yml");
        leaderboardLocationConfig = YamlConfiguration.loadConfiguration(leaderboardLocationFile);

        loadLeaderboardLocations();
        refreshTicks = readRefreshTicks();
        loadRotatingTitles();
        playerStatueManager = new PlayerStatueManager(this, getRankGUI(), getSkinHeadService());
        registerCommands();
        registerListeners();
        playerStatueManager.loadStatuesFromConfig();
        syncOnlinePlayers();
        startRefreshTask();
        startAssassinationExpiryTask();
        startHologramRotationTask();
        refreshPromotionBroadcaster();
        refreshLeaderboards();

        chiselLives = new ChiselLives(this);
        if (!chiselLives.enable()) {
            getLogger().severe("Disabling KingdomsBounty because ChiselLives failed to initialize.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        webSyncServer = new WebSyncServer(this, bountyDatabaseManager, leaderboardManager, chiselLives);
        webSyncServer.start();

        purchaseJobPoller = new PurchaseJobPoller(this, bountyDatabaseManager, chiselLives);
        purchaseJobPoller.start();

        getLogger().info("KingdomsBounty enabled. Kingdoms, bounties, and leaderboards are active.");
    }

    private void logConfiguredDatabaseModes() {
        String sharedMode = getConfig().getString("database.mode", "auto");
        String bountyMode = getConfig().getString("bounty-database.mode", sharedMode);
        String kingdomsMode = getConfig().getString("kingdoms-database.mode", sharedMode);
        String sharedHost = getConfig().getString("database.host", "localhost");
        String sharedDatabase = getConfig().getString("database.database");
        if (sharedDatabase == null || sharedDatabase.isBlank()) {
            sharedDatabase = getConfig().getString("database.name", "kingdomsbounty");
        }

        getLogger().info("Configured database modes: shared=" + sharedMode
                + ", bounty=" + bountyMode + ", kingdoms=" + kingdomsMode + '.');
        if (!"sqlite".equalsIgnoreCase(sharedMode)
                || !"sqlite".equalsIgnoreCase(bountyMode)
                || !"sqlite".equalsIgnoreCase(kingdomsMode)) {
            getLogger().info("Configured MySQL target: " + sharedHost + "/" + sharedDatabase + '.');
        }
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }

        if (assassinationExpiryTask != null) {
            assassinationExpiryTask.cancel();
            assassinationExpiryTask = null;
        }

        if (hologramRotationTask != null) {
            hologramRotationTask.cancel();
            hologramRotationTask = null;
        }

        if (hologramManager != null) {
            hologramManager.clearHolograms();
        }

        if (webSyncServer != null) {
            webSyncServer.stop();
            webSyncServer = null;
        }

        if (purchaseJobPoller != null) {
            purchaseJobPoller.stop();
            purchaseJobPoller = null;
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        if (chatManager != null) {
            chatManager.clear();
        }

        if (bountyDatabaseManager != null) {
            bountyDatabaseManager.close();
        }

        if (chiselLives != null) {
            chiselLives.disable();
            chiselLives = null;
        }

        super.onDisable();

        getLogger().info("KingdomsBounty disabled.");
    }

    public void setPlayersLocation(Location location) {
        playersLocation = location.clone();
        saveLocation("leaderboards.players", playersLocation);
        saveConfig();
        refreshLeaderboards();
    }

    public void setKingdomsLocation(Location location) {
        kingdomsLocation = location.clone();
        saveLocation("leaderboards.kingdoms", kingdomsLocation);
        saveConfig();
        refreshLeaderboards();
    }

    public boolean clearPlayersLocation() {
        boolean hadLocation = playersLocation != null;
        playersLocation = null;
        clearLocation("leaderboards.players");
        saveConfig();
        refreshLeaderboards();
        return hadLocation;
    }

    public boolean clearKingdomsLocation() {
        boolean hadLocation = kingdomsLocation != null;
        kingdomsLocation = null;
        clearLocation("leaderboards.kingdoms");
        saveConfig();
        refreshLeaderboards();
        return hadLocation;
    }

    public boolean clearAllLeaderboardLocations() {
        boolean removed = playersLocation != null || kingdomsLocation != null;
        playersLocation = null;
        kingdomsLocation = null;
        clearLocation("leaderboards.players");
        clearLocation("leaderboards.kingdoms");
        saveConfig();
        refreshLeaderboards();
        return removed;
    }

    public boolean reloadPlugin() {
        reloadConfig();
        loadLeaderboardLocations();
        refreshTicks = readRefreshTicks();
        loadRotatingTitles();

        if (!databaseManager.reload()) {
            return false;
        }

        if (chiselLives != null) {
            chiselLives.disable();
        }

        chiselLives = new ChiselLives(this);
        if (!chiselLives.enable()) {
            getLogger().severe("Failed to reload ChiselLives.");
            return false;
        }

        if (webSyncServer != null) {
            webSyncServer.stop();
        }

        webSyncServer = new WebSyncServer(this, bountyDatabaseManager, leaderboardManager, chiselLives);
        webSyncServer.start();

        if (purchaseJobPoller != null) {
            purchaseJobPoller.stop();
        }
        purchaseJobPoller = new PurchaseJobPoller(this, bountyDatabaseManager, chiselLives);
        purchaseJobPoller.start();

        startRefreshTask();
        startAssassinationExpiryTask();
        startHologramRotationTask();
        refreshLeaderboards();

        if (playerStatueManager == null) {
            playerStatueManager = new PlayerStatueManager(this, getRankGUI(), getSkinHeadService());
        }
        playerStatueManager.loadStatuesFromConfig();
        return true;
    }

    public synchronized ResetResult resetAllPluginData() {
        return resetAllPluginData(ResetMode.SEED_ONLINE_PLAYERS);
    }

    public synchronized ResetResult resetAllPluginData(ResetMode mode) {
        ResetMode effectiveMode = mode == null ? ResetMode.SEED_ONLINE_PLAYERS : mode;
        List<String> failures = new ArrayList<>();

        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }

        if (assassinationExpiryTask != null) {
            assassinationExpiryTask.cancel();
            assassinationExpiryTask = null;
        }

        if (hologramRotationTask != null) {
            hologramRotationTask.cancel();
            hologramRotationTask = null;
        }

        if (hologramManager != null) {
            hologramManager.clearHolograms();
        }

        if (webSyncServer != null) {
            webSyncServer.stop();
            webSyncServer = null;
        }

        if (purchaseJobPoller != null) {
            purchaseJobPoller.stop();
            purchaseJobPoller = null;
        }

        if (chiselLives != null) {
            chiselLives.disable();
            chiselLives = null;
        }

        com.chiselranks.DatabaseManager ranksDatabaseManager = getDatabaseManager();
        if (ranksDatabaseManager == null || !ranksDatabaseManager.resetAllData()) {
            failures.add("chiselranks");
        }

        if (bountyDatabaseManager == null || !bountyDatabaseManager.resetAllData()) {
            failures.add("bounty");
        }

        if (kingdomsDatabaseManager == null || !kingdomsDatabaseManager.resetAllData()) {
            failures.add("kingdoms");
        }

        if (databaseManager == null || !databaseManager.resetAllData()) {
            failures.add("leaderboards");
        }

        com.chisellives.DatabaseManager livesDatabaseManager = new com.chisellives.DatabaseManager(this);
        boolean livesReset = livesDatabaseManager.initialize() && livesDatabaseManager.resetAllData();
        livesDatabaseManager.close();
        if (!livesReset) {
            failures.add("chisel-lives");
        }

        resetConfigBackedData();
        applyConfigBackedResets();

        if (kingdomManager != null) {
            kingdomManager.reloadFromDatabase();
        }

        if (warManager != null) {
            warManager.reloadFromDatabase();
        }

        if (bountyManager != null) {
            bountyManager.clearCache();
        }

        if (!restartLivesAndWebSync()) {
            failures.add("runtime-restart");
        }

        int syncedPlayers = 0;
        if (effectiveMode.seedOnlinePlayers()) {
            syncedPlayers = syncOnlinePlayers();
        }

        startRefreshTask();
        startAssassinationExpiryTask();
        startHologramRotationTask();
        refreshLeaderboards();

        if (failures.isEmpty()) {
            if (effectiveMode.seedOnlinePlayers()) {
                return new ResetResult(true,
                        "All KingdomsBounty data was reset and recreated in MySQL. Synced "
                                + syncedPlayers + " online player(s). Use '/chiselmod reset hard' to keep tables empty.");
            }

            return new ResetResult(true,
                    "All KingdomsBounty data was reset and recreated in MySQL. No online players were seeded.");
        }

        return new ResetResult(false,
                "Reset ('" + effectiveMode.cliLabel() + "') completed with errors in: "
                        + String.join(", ", failures) + ". Check console logs.");
    }

    private boolean restartLivesAndWebSync() {
        chiselLives = new ChiselLives(this);
        if (!chiselLives.enable()) {
            getLogger().severe("Failed to restart ChiselLives after reset.");
            chiselLives = null;
            return false;
        }

        if (bountyDatabaseManager == null || leaderboardManager == null) {
            getLogger().severe("Failed to restart website sync after reset due to missing database managers.");
            return false;
        }

        webSyncServer = new WebSyncServer(this, bountyDatabaseManager, leaderboardManager, chiselLives);
        webSyncServer.start();

        purchaseJobPoller = new PurchaseJobPoller(this, bountyDatabaseManager, chiselLives);
        purchaseJobPoller.start();
        return true;
    }

    private void resetConfigBackedData() {
        getConfig().set("npc-statues", null);
        getConfig().set("shop-npcs", null);
        getConfig().set("rank-npc.enabled", false);
        saveConfig();

        if (playerStatueManager != null) {
            playerStatueManager.clearPersistedStatues();
        }
    }

    private void applyConfigBackedResets() {
        removeRankShopNpcs();

        if (playerStatueManager != null) {
            playerStatueManager.loadStatuesFromConfig();
        }
    }

    private void removeRankShopNpcs() {
        NamespacedKey rankNpcKey = new NamespacedKey(this, "rank-shop-npc");
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(rankNpcKey, PersistentDataType.BYTE)) {
                    entity.remove();
                }
            }
        }
    }

    public void refreshLeaderboards() {
        Bukkit.getScheduler().runTask(this, () -> {
            if (!isEnabled() || leaderboardManager == null || hologramManager == null) {
                return;
            }

            hologramManager.clearHolograms();

            CompletableFuture<List<LeaderboardEntry>> playersFuture = leaderboardManager.getTopPlayers();
            CompletableFuture<List<LeaderboardEntry>> kingdomsFuture = leaderboardManager.getTopKingdoms();

            playersFuture.thenCombine(kingdomsFuture, LeaderboardSnapshot::new)
                    .whenComplete((snapshot, throwable) -> {
                        if (throwable != null) {
                            logRefreshFailure(throwable);
                            return;
                        }

                        logRefreshRecoveryIfNeeded();

                        Bukkit.getScheduler().runTask(this, () -> applySnapshot(snapshot));
                    });
        });
    }

    private void applySnapshot(LeaderboardSnapshot snapshot) {
        if (!isEnabled()) {
            return;
        }

        hologramManager.updateData(resolvePlayerNames(snapshot.players()), snapshot.kingdoms());

        if (playersLocation != null) {
            hologramManager.spawnPlayerLeaderboard(playersLocation);
        }

        if (kingdomsLocation != null) {
            hologramManager.spawnKingdomLeaderboard(kingdomsLocation);
        }
    }

    private void logRefreshFailure(Throwable throwable) {
        leaderboardRefreshHealthy = false;

        long now = System.currentTimeMillis();
        if (now - lastRefreshFailureLogAt < REFRESH_FAILURE_LOG_COOLDOWN_MS) {
            return;
        }
        lastRefreshFailureLogAt = now;

        getLogger().warning("[Leaderboards] Refresh skipped because the database is unavailable: "
                + summarizeThrowable(throwable) + ". Retrying automatically.");
    }

    private void logRefreshRecoveryIfNeeded() {
        if (!leaderboardRefreshHealthy) {
            leaderboardRefreshHealthy = true;
            getLogger().info("[Leaderboards] Refresh recovered.");
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

    private void registerCommands() {
        registerCommandSafely("leaderboard", () -> {
            PluginCommand command = getCommand("leaderboard");
            if (command == null) {
                getLogger().warning("/leaderboard command is missing from plugin.yml.");
                return;
            }

            LeaderboardCommand leaderboardCommand = new LeaderboardCommand(this);
            command.setExecutor(leaderboardCommand);
            command.setTabCompleter(leaderboardCommand);
        });

        registerCommandSafely("chiselhelp", () -> {
            PluginCommand helpCommand = getCommand("chiselhelp");
            if (helpCommand == null) {
                getLogger().warning("/chiselhelp command is missing from plugin.yml.");
                return;
            }

            HelpCommand pluginHelpCommand = new HelpCommand();
            helpCommand.setExecutor(pluginHelpCommand);
            helpCommand.setTabCompleter(pluginHelpCommand);
        });

        registerCommandSafely("chiselmod", () -> {
            PluginCommand chiselModCommand = getCommand("chiselmod");
            if (chiselModCommand == null) {
                getLogger().warning("/chiselmod command is missing from plugin.yml.");
                return;
            }

            ChiselModCommand commandHandler = new ChiselModCommand(this);
            chiselModCommand.setExecutor(commandHandler);
            chiselModCommand.setTabCompleter(commandHandler);
        });

        registerCommandSafely("war", () -> {
            PluginCommand warPluginCommand = getCommand("war");
            if (warPluginCommand == null) {
                getLogger().warning("/war command is missing from plugin.yml.");
                return;
            }

            WarCommand warCommand = new WarCommand(warManager, kingdomManager, allyManager);
            warPluginCommand.setExecutor(warCommand);
            warPluginCommand.setTabCompleter(warCommand);
        });

        registerCommandSafely("ally", () -> {
            PluginCommand allyPluginCommand = getCommand("ally");
            if (allyPluginCommand == null) {
                getLogger().warning("/ally command is missing from plugin.yml.");
                return;
            }

            if (allyManager != null) {
                AllyCommand allyCommand = new AllyCommand(allyManager, kingdomManager);
                allyPluginCommand.setExecutor(allyCommand);
                allyPluginCommand.setTabCompleter(allyCommand);
            }
        });

        registerCommandSafely("statue", () -> {
            PluginCommand statueCommand = getCommand("statue");
            if (statueCommand == null) {
                getLogger().warning("/statue command is missing from plugin.yml.");
                return;
            }

            if (playerStatueManager != null) {
                statueCommand.setExecutor(playerStatueManager);
                statueCommand.setTabCompleter(playerStatueManager);
            }
        });
    }

    private void registerCommandSafely(String commandName, Runnable registration) {
        try {
            registration.run();
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "Failed to register /" + commandName + " command. Continuing without it.", throwable);
        }
    }

    public DatabaseManager getLeaderboardDatabaseManager() {
        return databaseManager;
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new HelpListener(), this);
        if (getSkinHeadService() != null && databaseManager != null) {
            getServer().getPluginManager().registerEvents(new SkinCacheListener(this, getSkinHeadService(), databaseManager), this);
        }
        if (chatManager != null && bountyDatabaseManager != null) {
            getServer().getPluginManager().registerEvents(new JoinListener(chatManager, bountyDatabaseManager, kingdomManager), this);
        }

        if (warManager != null && kingdomManager != null) {
            getServer().getPluginManager().registerEvents(new WarKillListener(warManager, kingdomManager), this);
        }
        if (assassinationManager != null) {
            getServer().getPluginManager().registerEvents(new AssassinationListener(assassinationManager), this);
        }

        if (playerStatueManager != null) {
            getServer().getPluginManager().registerEvents(playerStatueManager, this);
        }
    }

    private boolean initializeKingdomsSystem() {
        kingdomsDatabaseManager = new com.beyondminer.kingdoms.database.DatabaseManager(getDataFolder(), this);
        if (!kingdomsDatabaseManager.isInitialized()) {
            return false;
        }

        kingdomManager = new KingdomManager(kingdomsDatabaseManager);
        inviteManager = new InviteManager();
        chatManager = new ChatManager();
        allyManager = new AllyManager(kingdomManager, kingdomsDatabaseManager);

        KingdomCommand kingdomCommand = new KingdomCommand(this, kingdomManager, inviteManager, chatManager);

        PluginCommand kingdomPluginCommand = getCommand("kingdom");
        if (kingdomPluginCommand != null) {
            kingdomPluginCommand.setExecutor(kingdomCommand);
            kingdomPluginCommand.setTabCompleter(kingdomCommand);
        } else {
            getLogger().warning("/kingdom command is missing from plugin.yml.");
        }

        PluginCommand kcPluginCommand = getCommand("kc");
        if (kcPluginCommand != null) {
            kcPluginCommand.setExecutor(kingdomCommand);
            kcPluginCommand.setTabCompleter(kingdomCommand);
        } else {
            getLogger().warning("/kc command is missing from plugin.yml.");
        }

        getServer().getPluginManager().registerEvents(new ChatListener(kingdomManager, chatManager), this);
        getServer().getPluginManager().registerEvents(kingdomCommand, this);
        return true;
    }

    private boolean initializeBountySystem() {
        bountyDatabaseManager = new com.beyondminer.bounty.database.DatabaseManager(getDataFolder(), this);
        if (!bountyDatabaseManager.isInitialized()) {
            return false;
        }

        bountyManager = new BountyManager(bountyDatabaseManager);
        kingdomIntegrationManager = new KingdomIntegrationManager(bountyDatabaseManager, bountyManager, kingdomManager);
        bountyLeaderboardManager = new com.beyondminer.bounty.managers.LeaderboardManager(
                bountyDatabaseManager,
                kingdomIntegrationManager
        );
        contractManager = new ContractManager(bountyDatabaseManager);

        warManager = new WarManager(
            bountyDatabaseManager,
            kingdomManager,
            bountyManager,
            kingdomIntegrationManager,
            allyManager
        );
        assassinationManager = new AssassinationManager(bountyDatabaseManager, bountyManager);

        BountyCommand bountyCommand = new BountyCommand(
            bountyManager,
            bountyLeaderboardManager,
            contractManager,
            kingdomIntegrationManager,
            assassinationManager
        );

        PluginCommand bountyPluginCommand = getCommand("bounty");
        if (bountyPluginCommand != null) {
            bountyPluginCommand.setExecutor(bountyCommand);
            bountyPluginCommand.setTabCompleter(bountyCommand);
        } else {
            getLogger().warning("/bounty command is missing from plugin.yml.");
        }

        getServer().getPluginManager().registerEvents(
            new PlayerKillListener(
                    bountyManager,
                    kingdomIntegrationManager,
                    bountyDatabaseManager,
                    warManager,
                    assassinationManager
            ),
            this
        );
        return true;
    }

    private void startRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }

        refreshTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshLeaderboards, refreshTicks, refreshTicks);
    }

    private void startAssassinationExpiryTask() {
        if (assassinationExpiryTask != null) {
            assassinationExpiryTask.cancel();
        }

        if (assassinationManager == null) {
            return;
        }

        assassinationExpiryTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::processExpiredHitContracts,
                20L * 60L,
                20L * 60L
        );
    }

    private void startHologramRotationTask() {
        if (hologramRotationTask != null) {
            hologramRotationTask.cancel();
        }

        hologramRotationTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!isEnabled() || hologramManager == null) {
                return;
            }

            Component playerTitle = rotatingPlayerTitles.get(hologramRotationIndex % rotatingPlayerTitles.size());
            Component kingdomTitle = rotatingKingdomTitles.get(hologramRotationIndex % rotatingKingdomTitles.size());
            hologramManager.rotateTitles(playerTitle, kingdomTitle);
            hologramRotationIndex = (hologramRotationIndex + 1) % Math.max(rotatingPlayerTitles.size(), rotatingKingdomTitles.size());
        }, 40L, 40L);
    }

    private void processExpiredHitContracts() {
        List<AssassinationContract> expiredContracts = assassinationManager.expireAndApplySurvivorBounties();
        if (expiredContracts.isEmpty()) {
            return;
        }

        for (AssassinationContract contract : expiredContracts) {
            Player target = Bukkit.getPlayer(contract.getTargetUuid());
            if (target != null) {
                target.sendMessage("§d[Hit] §fYou survived the hit. §e" + contract.getAmount()
                        + " §fbounty has been added to your head.");
            }

            Player requester = Bukkit.getPlayer(contract.getRequesterUuid());
            if (requester != null) {
                requester.sendMessage("§d[Hit] §fYour hit request on §a" + contract.getTargetName()
                        + " §fexpired. The bounty was placed on the target.");
            }

            if (contract.getAcceptedByUuid() != null) {
                Player hitman = Bukkit.getPlayer(contract.getAcceptedByUuid());
                if (hitman != null) {
                    hitman.sendMessage("§d[Hit] §cYour accepted hit on §a" + contract.getTargetName()
                            + " §cexpired. The target survived.");
                }
            }
        }
    }

    private long readRefreshTicks() {
        int refreshSeconds = getConfig().getInt("leaderboards.refresh-seconds", DEFAULT_REFRESH_SECONDS);
        if (refreshSeconds < 1) {
            getLogger().warning("leaderboards.refresh-seconds must be >= 1. Using default value " + DEFAULT_REFRESH_SECONDS + ".");
            refreshSeconds = DEFAULT_REFRESH_SECONDS;
        }

        return refreshSeconds * 20L;
    }

    private void loadLeaderboardLocations() {
        playersLocation = loadLocation("leaderboards.players");
        kingdomsLocation = loadLocation("leaderboards.kingdoms");
    }

    private void loadRotatingTitles() {
        rotatingPlayerTitles = readTitleList("leaderboards.rotating.players", "Top Players Leaderboard", "Bounties Update Live", "Tracked In Real Time");
        rotatingKingdomTitles = readTitleList("leaderboards.rotating.kingdoms", "Top Kingdoms Leaderboard", "Power Rankings Live", "Territory Leaders");
        hologramRotationIndex = 0;
    }

    private int syncOnlinePlayers() {
        if (bountyDatabaseManager == null) {
            return 0;
        }

        int syncedPlayers = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            bountyDatabaseManager.savePlayer(player.getUniqueId(), player.getName());
            syncedPlayers++;
        }

        return syncedPlayers;
    }

    private Location loadLocation(String path) {
        String worldName = leaderboardLocationConfig.getString(path + ".world", getConfig().getString(path + ".world"));
        if (worldName == null || worldName.isBlank()) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("World '" + worldName + "' for " + path + " is not loaded.");
            return null;
        }

        double x = leaderboardLocationConfig.getDouble(path + ".x", getConfig().getDouble(path + ".x"));
        double y = leaderboardLocationConfig.getDouble(path + ".y", getConfig().getDouble(path + ".y"));
        double z = leaderboardLocationConfig.getDouble(path + ".z", getConfig().getDouble(path + ".z"));
        float yaw = (float) leaderboardLocationConfig.getDouble(path + ".yaw", getConfig().getDouble(path + ".yaw", 0.0D));
        float pitch = (float) leaderboardLocationConfig.getDouble(path + ".pitch", getConfig().getDouble(path + ".pitch", 0.0D));

        return new Location(world, x, y, z, yaw, pitch);
    }

    private void saveLocation(String path, Location location) {
        if (location.getWorld() == null) {
            return;
        }

        getConfig().set(path + ".world", location.getWorld().getName());
        getConfig().set(path + ".x", location.getX());
        getConfig().set(path + ".y", location.getY());
        getConfig().set(path + ".z", location.getZ());
        getConfig().set(path + ".yaw", location.getYaw());
        getConfig().set(path + ".pitch", location.getPitch());
        leaderboardLocationConfig.set(path + ".world", location.getWorld().getName());
        leaderboardLocationConfig.set(path + ".x", location.getX());
        leaderboardLocationConfig.set(path + ".y", location.getY());
        leaderboardLocationConfig.set(path + ".z", location.getZ());
        leaderboardLocationConfig.set(path + ".yaw", location.getYaw());
        leaderboardLocationConfig.set(path + ".pitch", location.getPitch());
        saveLeaderboardLocations();
    }

    private void clearLocation(String path) {
        getConfig().set(path, null);
        leaderboardLocationConfig.set(path, null);
        saveLeaderboardLocations();
    }

    private void saveLeaderboardLocations() {
        try {
            leaderboardLocationConfig.save(leaderboardLocationFile);
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "Failed to save leaderboard locations.", exception);
        }
    }

    private List<Component> readTitleList(String path, String... fallback) {
        List<String> configured = getConfig().getStringList(path);
        List<String> source = configured.isEmpty() ? List.of(fallback) : configured;
        List<Component> titles = new ArrayList<>(source.size());
        for (String line : source) {
            titles.add(Component.text(line, NamedTextColor.AQUA));
        }
        return titles;
    }

    private List<LeaderboardEntry> resolvePlayerNames(List<LeaderboardEntry> players) {
        List<LeaderboardEntry> resolved = new ArrayList<>(players.size());
        for (LeaderboardEntry entry : players) {
            resolved.add(new LeaderboardEntry(resolveDisplayName(entry.name()), entry.bounty()));
        }
        return resolved;
    }

    private String resolveDisplayName(String rawNameOrUuid) {
        try {
            UUID uuid = UUID.fromString(rawNameOrUuid);
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String name = offlinePlayer.getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (IllegalArgumentException ignored) {
            return rawNameOrUuid;
        }

        return rawNameOrUuid;
    }

    public record ResetResult(boolean success, String message) {
    }

    public enum ResetMode {
        SEED_ONLINE_PLAYERS("seed-online", true),
        HARD("hard", false);

        private final String cliLabel;
        private final boolean seedOnlinePlayers;

        ResetMode(String cliLabel, boolean seedOnlinePlayers) {
            this.cliLabel = cliLabel;
            this.seedOnlinePlayers = seedOnlinePlayers;
        }

        public String cliLabel() {
            return cliLabel;
        }

        public boolean seedOnlinePlayers() {
            return seedOnlinePlayers;
        }
    }

    private record LeaderboardSnapshot(List<LeaderboardEntry> players, List<LeaderboardEntry> kingdoms) {
    }

}
