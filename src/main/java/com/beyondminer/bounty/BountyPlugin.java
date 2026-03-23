package com.beyondminer.bounty;

import com.beyondminer.bounty.commands.BountyCommand;
import com.beyondminer.bounty.database.DatabaseManager;
import com.beyondminer.bounty.listeners.PlayerKillListener;
import com.beyondminer.bounty.managers.BountyManager;
import com.beyondminer.bounty.managers.ContractManager;
import com.beyondminer.bounty.managers.KingdomIntegrationManager;
import com.beyondminer.bounty.managers.LeaderboardManager;
import org.bukkit.plugin.java.JavaPlugin;

public class BountyPlugin extends JavaPlugin {
    private static BountyPlugin instance;
    private static DatabaseManager databaseManager;
    private BountyManager bountyManager;
    private LeaderboardManager leaderboardManager;
    private ContractManager contractManager;
    private KingdomIntegrationManager kingdomIntegrationManager;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("§b[BountyPlugin] Loading BountyPlugin v1.0...");

        databaseManager = new DatabaseManager(getDataFolder());
        bountyManager = new BountyManager(databaseManager);
        kingdomIntegrationManager = new KingdomIntegrationManager(databaseManager, bountyManager);
        leaderboardManager = new LeaderboardManager(databaseManager, kingdomIntegrationManager);
        contractManager = new ContractManager(databaseManager);

        BountyCommand bountyCommand = new BountyCommand(
            bountyManager,
            leaderboardManager,
            contractManager,
            kingdomIntegrationManager
        );

        getCommand("bounty").setExecutor(bountyCommand);
        getCommand("bounty").setTabCompleter(bountyCommand);

        getServer().getPluginManager().registerEvents(
            new PlayerKillListener(bountyManager, kingdomIntegrationManager, databaseManager),
            this
        );

        getLogger().info("§b[BountyPlugin] §aSuccessfully loaded!");
    }

    @Override
    public void onDisable() {
        getLogger().info("§b[BountyPlugin] Saving data and disabling...");

        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("§b[BountyPlugin] §cDisabled!");
    }

    public static BountyPlugin getInstance() {
        return instance;
    }

    public static DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public BountyManager getBountyManager() {
        return bountyManager;
    }

    public LeaderboardManager getLeaderboardManager() {
        return leaderboardManager;
    }

    public ContractManager getContractManager() {
        return contractManager;
    }

    public KingdomIntegrationManager getKingdomIntegrationManager() {
        return kingdomIntegrationManager;
    }
}
