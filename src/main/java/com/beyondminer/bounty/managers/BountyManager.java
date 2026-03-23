package com.beyondminer.bounty.managers;

import com.beyondminer.bounty.database.DatabaseManager;
import com.beyondminer.bounty.models.PlayerBounty;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BountyManager {
    private final DatabaseManager database;
    private final Map<UUID, PlayerBounty> playerBountyCache;
    private static final int BASE_KILL_REWARD = 100;
    private static final double KILL_BONUS_PERCENTAGE = 0.20;

    public BountyManager(DatabaseManager database) {
        this.database = database;
        this.playerBountyCache = new ConcurrentHashMap<>();
    }

    public synchronized PlayerBounty getPlayerBounty(UUID playerUuid) {
        return playerBountyCache.computeIfAbsent(playerUuid, database::loadPlayerBounty);
    }

    public synchronized int getBountyValue(UUID playerUuid) {
        return getPlayerBounty(playerUuid).getBounty();
    }

    public void trackPlayer(Player player) {
        if (player == null) {
            return;
        }

        database.savePlayer(player.getUniqueId(), player.getName());
    }

    public UUID resolvePlayerUuid(String playerName) {
        return database.findPlayerUuidByName(playerName);
    }

    public String getStoredPlayerName(UUID playerUuid) {
        return database.getPlayerName(playerUuid);
    }

    public synchronized void addBounty(UUID playerUuid, int amount) {
        PlayerBounty playerBounty = getPlayerBounty(playerUuid);
        playerBounty.addBounty(amount);
        savePlayerBountyAsync(playerUuid, playerBounty);
    }

    public synchronized void resetBounty(UUID playerUuid) {
        PlayerBounty playerBounty = getPlayerBounty(playerUuid);
        playerBounty.resetBounty();
        savePlayerBountyAsync(playerUuid, playerBounty);
    }

    public synchronized void incrementKills(UUID playerUuid) {
        PlayerBounty playerBounty = getPlayerBounty(playerUuid);
        playerBounty.incrementKills();
        savePlayerBountyAsync(playerUuid, playerBounty);
    }

    public synchronized void incrementDeaths(UUID playerUuid) {
        PlayerBounty playerBounty = getPlayerBounty(playerUuid);
        playerBounty.incrementDeaths();
        savePlayerBountyAsync(playerUuid, playerBounty);
    }

    public int calculateKillReward(UUID victimUuid) {
        int victimBounty = getBountyValue(victimUuid);
        int baseReward = BASE_KILL_REWARD;
        int bonusReward = (int) (victimBounty * KILL_BONUS_PERCENTAGE);
        return baseReward + bonusReward;
    }

    public synchronized void applyKillReward(UUID killerUuid, UUID victimUuid) {
        int reward = calculateKillReward(victimUuid);
        applyKillRewardWithAmount(killerUuid, victimUuid, reward);
    }

    /** Applies a pre-calculated reward (e.g. including war bonus) without recalculating. */
    public synchronized void applyKillRewardWithAmount(UUID killerUuid, UUID victimUuid, int reward) {
        PlayerBounty killerBounty = getPlayerBounty(killerUuid);
        killerBounty.addBounty(reward);
        killerBounty.incrementKills();

        PlayerBounty victimBounty = getPlayerBounty(victimUuid);
        victimBounty.resetBounty();
        victimBounty.incrementDeaths();

        savePlayerBountyAsync(killerUuid, killerBounty);
        savePlayerBountyAsync(victimUuid, victimBounty);
    }

    /**
     * Applies a pre-calculated reward during war while preserving victim bounty
     * so kingdom bounty totals are not reduced mid-war.
     */
    public synchronized void applyKillRewardDuringWar(UUID killerUuid, UUID victimUuid, int reward) {
        PlayerBounty killerBounty = getPlayerBounty(killerUuid);
        killerBounty.addBounty(reward);
        killerBounty.incrementKills();

        PlayerBounty victimBounty = getPlayerBounty(victimUuid);
        victimBounty.incrementDeaths();

        savePlayerBountyAsync(killerUuid, killerBounty);
        savePlayerBountyAsync(victimUuid, victimBounty);
    }

    public synchronized void placeBountyOnPlayer(UUID targetUuid, int amount) {
        addBounty(targetUuid, amount);
    }

    public synchronized void clearCache() {
        playerBountyCache.clear();
    }

    private void savePlayerBountyAsync(UUID playerUuid, PlayerBounty playerBounty) {
        new Thread(() -> savePlayerBounty(playerUuid, playerBounty)).start();
    }

    private void savePlayerBounty(UUID playerUuid, PlayerBounty playerBounty) {
        database.savePlayerBounty(
                playerUuid,
                playerBounty.getBounty(),
                playerBounty.getKills(),
                playerBounty.getDeaths()
        );
    }
}
