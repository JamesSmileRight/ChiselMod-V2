package com.beyondminer.war.managers;

import com.beyondminer.bounty.database.DatabaseManager;
import com.beyondminer.bounty.managers.BountyManager;
import com.beyondminer.bounty.managers.KingdomIntegrationManager;
import com.beyondminer.kingdoms.managers.AllyManager;
import com.beyondminer.kingdoms.managers.KingdomManager;
import com.beyondminer.kingdoms.models.Kingdom;
import com.beyondminer.war.models.War;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WarManager {

    private static final double WAR_KILL_BONUS = 0.50;
    public static final int WAR_WINNER_MEMBER_BOUNTY_REWARD = 200;
    private static final double WAR_VICTORY_KINGDOM_REWARD_PERCENTAGE = 0.50;

    private final DatabaseManager database;
    private final KingdomManager kingdomManager;
    private final BountyManager bountyManager;
    private final KingdomIntegrationManager kingdomIntegrationManager;
    private final AllyManager allyManager;
    private final Map<String, War> activeWars;

    public enum EndWarStatus {
        SUCCESS,
        NOT_IN_KINGDOM,
        NOT_LEADER,
        NOT_AT_WAR
    }

    public record WarEndResult(
            EndWarStatus status,
            String endedKingdom,
            String opponentKingdom,
            String winnerKingdom,
            int winnerKills,
            int loserKills,
            int rewardedMembers,
            int memberReward,
            int kingdomVictoryReward
    ) {
        public boolean success() {
            return status == EndWarStatus.SUCCESS;
        }

        public boolean hasWinner() {
            return winnerKingdom != null;
        }
    }

    public record LeaderKillEndResult(
            boolean ended,
            String winnerKingdom,
            String loserKingdom,
            int rewardedMembers,
            int memberReward,
            int kingdomVictoryReward
    ) {
    }

    public WarManager(DatabaseManager database, KingdomManager kingdomManager) {
        this(database, kingdomManager, null, null, null);
    }

    public WarManager(DatabaseManager database, KingdomManager kingdomManager,
                      BountyManager bountyManager, KingdomIntegrationManager kingdomIntegrationManager) {
        this(database, kingdomManager, bountyManager, kingdomIntegrationManager, null);
    }

    public WarManager(DatabaseManager database, KingdomManager kingdomManager,
                      BountyManager bountyManager, KingdomIntegrationManager kingdomIntegrationManager,
                      AllyManager allyManager) {
        this.database = database;
        this.kingdomManager = kingdomManager;
        this.bountyManager = bountyManager;
        this.kingdomIntegrationManager = kingdomIntegrationManager;
        this.allyManager = allyManager;
        this.activeWars = new ConcurrentHashMap<>();
        for (War war : database.getActiveWars()) {
            activeWars.put(toWarKey(war.getKingdomA(), war.getKingdomB()), war);
        }
    }

    /**
     * Declares war on a target kingdom. Only leaders can declare.
     * Returns false if the caller is not a leader, the kingdom doesn't exist,
     * they are in the same kingdom, or a war is already pending/active.
     */
    public boolean declareWar(UUID declarerUuid, String targetKingdomName) {
        Kingdom declarerKingdom = kingdomManager.getPlayerKingdom(declarerUuid);
        if (declarerKingdom == null) return false;
        if (!declarerKingdom.getLeader().equals(declarerUuid)) return false;

        Kingdom targetKingdom = kingdomManager.getKingdom(targetKingdomName);
        if (targetKingdom == null) return false;
        if (declarerKingdom.getName().equalsIgnoreCase(targetKingdom.getName())) return false;

        if (allyManager != null && allyManager.areAllied(declarerKingdom.getName(), targetKingdom.getName())) {
            return false;
        }

        if (areAtWar(declarerKingdom.getName(), targetKingdom.getName())) return false;
        if (database.hasPendingWarRequest(declarerKingdom.getName(), targetKingdom.getName())) return false;
        if (database.hasPendingWarRequest(targetKingdom.getName(), declarerKingdom.getName())) return false;

        database.insertWarRequest(declarerKingdom.getName(), targetKingdom.getName(), System.currentTimeMillis());
        return true;
    }

    /**
     * Accepts a pending war declaration. Only the target kingdom's leader can accept.
     */
    public boolean acceptWar(UUID accepterUuid, String attackerKingdomName) {
        Kingdom accepterKingdom = kingdomManager.getPlayerKingdom(accepterUuid);
        if (accepterKingdom == null) return false;
        if (!accepterKingdom.getLeader().equals(accepterUuid)) return false;

        Kingdom attackerKingdom = kingdomManager.getKingdom(attackerKingdomName);
        if (attackerKingdom == null) return false;

        if (!database.hasPendingWarRequest(attackerKingdom.getName(), accepterKingdom.getName())) return false;

        database.activateWar(attackerKingdom.getName(), accepterKingdom.getName());
        activeWars.put(
            toWarKey(attackerKingdom.getName(), accepterKingdom.getName()),
            new War(attackerKingdom.getName(), accepterKingdom.getName(), System.currentTimeMillis(), true)
        );
        return true;
    }

    /**
     * Ends the active war for the player's kingdom. Only leaders can end wars.
     */
    public boolean endWar(UUID playerUuid) {
        return endWarWithResult(playerUuid).success();
    }

    public WarEndResult endWarWithResult(UUID playerUuid) {
        Kingdom kingdom = kingdomManager.getPlayerKingdom(playerUuid);
        if (kingdom == null) {
            return new WarEndResult(
                    EndWarStatus.NOT_IN_KINGDOM,
                    null,
                    null,
                    null,
                    0,
                    0,
                    0,
                    WAR_WINNER_MEMBER_BOUNTY_REWARD,
                    0
            );
        }

        if (!kingdom.getLeader().equals(playerUuid)) {
            return new WarEndResult(
                    EndWarStatus.NOT_LEADER,
                    kingdom.getName(),
                    null,
                    null,
                    0,
                    0,
                    0,
                    WAR_WINNER_MEMBER_BOUNTY_REWARD,
                    0
            );
        }

        War activeWar = getActiveWarForKingdom(kingdom.getName());
        if (activeWar == null) {
            return new WarEndResult(
                    EndWarStatus.NOT_AT_WAR,
                    kingdom.getName(),
                    null,
                    null,
                    0,
                    0,
                    0,
                    WAR_WINNER_MEMBER_BOUNTY_REWARD,
                    0
            );
        }

        int killsByA = database.countWarKills(
                activeWar.getKingdomA(),
                activeWar.getKingdomB(),
                activeWar.getStartTime()
        );
        int killsByB = database.countWarKills(
                activeWar.getKingdomB(),
                activeWar.getKingdomA(),
                activeWar.getStartTime()
        );

        String winnerKingdom = null;
        String loserKingdom = null;
        int winnerKills = 0;
        int loserKills = 0;
        int rewardedMembers = 0;
        int kingdomVictoryReward = 0;

        if (killsByA > killsByB) {
            winnerKingdom = activeWar.getKingdomA();
            loserKingdom = activeWar.getKingdomB();
            winnerKills = killsByA;
            loserKills = killsByB;
        } else if (killsByB > killsByA) {
            winnerKingdom = activeWar.getKingdomB();
            loserKingdom = activeWar.getKingdomA();
            winnerKills = killsByB;
            loserKills = killsByA;
        }

        if (winnerKingdom != null) {
            rewardedMembers = rewardWinningMembers(winnerKingdom);
            kingdomVictoryReward = rewardWinningKingdom(winnerKingdom, loserKingdom);
        }

        boolean ended = database.endWarForKingdom(kingdom.getName());
        if (ended) {
            activeWars.entrySet().removeIf(entry -> entry.getValue().involves(kingdom.getName()));
        }

        EndWarStatus status = ended ? EndWarStatus.SUCCESS : EndWarStatus.NOT_AT_WAR;
        return new WarEndResult(
                status,
                kingdom.getName(),
                activeWar.getOpponent(kingdom.getName()),
                winnerKingdom,
                winnerKills,
                loserKills,
                rewardedMembers,
                WAR_WINNER_MEMBER_BOUNTY_REWARD,
                kingdomVictoryReward
        );
    }

    public LeaderKillEndResult endWarByLeaderKill(String loserKingdomName, String winnerKingdomName) {
        if (loserKingdomName == null || loserKingdomName.isBlank()
                || winnerKingdomName == null || winnerKingdomName.isBlank()) {
            return null;
        }

        War activeWar = getActiveWarForKingdom(loserKingdomName);
        if (activeWar == null || !activeWar.involves(winnerKingdomName)) {
            return null;
        }

        int rewardedMembers = rewardWinningMembers(winnerKingdomName);
        int kingdomVictoryReward = rewardWinningKingdom(winnerKingdomName, loserKingdomName);

        boolean ended = database.endWarForKingdom(loserKingdomName);
        if (ended) {
            activeWars.entrySet().removeIf(entry -> entry.getValue().involves(loserKingdomName));
        }

        return new LeaderKillEndResult(
                ended,
                winnerKingdomName,
                loserKingdomName,
                rewardedMembers,
                WAR_WINNER_MEMBER_BOUNTY_REWARD,
                kingdomVictoryReward
        );
    }

    /** Returns all currently active wars. */
    public List<War> getActiveWars() {
        return new ArrayList<>(activeWars.values());
    }

    public void reloadFromDatabase() {
        activeWars.clear();
        for (War war : database.getActiveWars()) {
            activeWars.put(toWarKey(war.getKingdomA(), war.getKingdomB()), war);
        }
    }

    public boolean areAtWar(String kingdom1, String kingdom2) {
        return activeWars.containsKey(toWarKey(kingdom1, kingdom2));
    }

    /**
     * Applies the +50% war bonus on top of the base reward when the two kingdoms are at war.
     * Returns the original base reward if they are not at war.
     */
    public int applyWarBonus(int baseReward, String killerKingdom, String victimKingdom) {
        if (killerKingdom == null || victimKingdom == null) return baseReward;
        if (!areAtWar(killerKingdom, victimKingdom)) return baseReward;
        return baseReward + (int) (baseReward * WAR_KILL_BONUS);
    }

    /** Records a kill that occurred during an active war. */
    public void recordWarKill(UUID killerUuid, UUID victimUuid, String killerKingdom, String victimKingdom) {
        database.insertWarKill(killerUuid, victimUuid, killerKingdom, victimKingdom, System.currentTimeMillis());
    }

    private War getActiveWarForKingdom(String kingdomName) {
        for (War war : activeWars.values()) {
            if (war.involves(kingdomName)) {
                return war;
            }
        }
        return null;
    }

    private int rewardWinningMembers(String winnerKingdomName) {
        if (bountyManager == null) {
            return 0;
        }

        Kingdom winnerKingdom = kingdomManager.getKingdom(winnerKingdomName);
        if (winnerKingdom == null) {
            return 0;
        }

        int rewarded = 0;
        for (UUID memberUuid : winnerKingdom.getMembers()) {
            bountyManager.addBounty(memberUuid, WAR_WINNER_MEMBER_BOUNTY_REWARD);
            rewarded++;
        }
        return rewarded;
    }

    private int rewardWinningKingdom(String winnerKingdomName, String loserKingdomName) {
        if (kingdomIntegrationManager == null || loserKingdomName == null) {
            return 0;
        }

        int losingKingdomBounty = Math.max(0, kingdomIntegrationManager.getKingdomBounty(loserKingdomName));
        int victoryReward = (int) Math.floor(losingKingdomBounty * WAR_VICTORY_KINGDOM_REWARD_PERCENTAGE);
        if (victoryReward > 0) {
            kingdomIntegrationManager.placeBountyOnKingdom(winnerKingdomName, victoryReward);
        }
        return victoryReward;
    }

    private String toWarKey(String kingdomA, String kingdomB) {
        String normalizedA = kingdomA.toLowerCase();
        String normalizedB = kingdomB.toLowerCase();
        return normalizedA.compareTo(normalizedB) <= 0
                ? normalizedA + ':' + normalizedB
                : normalizedB + ':' + normalizedA;
    }
}
