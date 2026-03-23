package com.beyondminer.bounty.listeners;

import com.beyondminer.assassination.managers.AssassinationManager;
import com.beyondminer.bounty.database.DatabaseManager;
import com.beyondminer.bounty.managers.BountyManager;
import com.beyondminer.bounty.managers.KingdomIntegrationManager;
import com.beyondminer.war.managers.WarManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class PlayerKillListener implements Listener {
    private final BountyManager bountyManager;
    private final KingdomIntegrationManager kingdomIntegration;
    private final DatabaseManager databaseManager;
    private final WarManager warManager;
    private final AssassinationManager assassinationManager;

    public PlayerKillListener(BountyManager bountyManager, KingdomIntegrationManager kingdomIntegration, DatabaseManager databaseManager) {
        this(bountyManager, kingdomIntegration, databaseManager, null, null);
    }

    public PlayerKillListener(BountyManager bountyManager, KingdomIntegrationManager kingdomIntegration,
                               DatabaseManager databaseManager, WarManager warManager) {
        this(bountyManager, kingdomIntegration, databaseManager, warManager, null);
    }

    public PlayerKillListener(BountyManager bountyManager, KingdomIntegrationManager kingdomIntegration,
                              DatabaseManager databaseManager, WarManager warManager,
                              AssassinationManager assassinationManager) {
        this.bountyManager = bountyManager;
        this.kingdomIntegration = kingdomIntegration;
        this.databaseManager = databaseManager;
        this.warManager = warManager;
        this.assassinationManager = assassinationManager;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null) {
            return;
        }

        String killerKingdom = kingdomIntegration.getPlayerKingdom(killer);
        String victimKingdom = kingdomIntegration.getPlayerKingdom(victim);
        boolean contractKill = assassinationManager != null
            && assassinationManager.isAcceptedContractKill(killer.getUniqueId(), victim.getUniqueId());

        if (!isValidKill(killer, victim, killerKingdom, victimKingdom, contractKill)) {
            return;
        }

        // Save player data asynchronously to avoid main thread blocking
        new Thread(() -> {
            databaseManager.savePlayer(killer.getUniqueId(), killer.getName());
            databaseManager.savePlayer(victim.getUniqueId(), victim.getName());
        }).start();

        int victimBountyAtDeath = bountyManager.getBountyValue(victim.getUniqueId());
        int baseReward = bountyManager.calculateKillReward(victim.getUniqueId());
        int bonusFromVictimBounty = Math.max(0, baseReward - 100);
        int finalReward = baseReward;
        boolean warKill = false;

        if (warManager != null) {
            if (killerKingdom != null && victimKingdom != null
                    && warManager.areAtWar(killerKingdom, victimKingdom)) {
                finalReward = warManager.applyWarBonus(baseReward, killerKingdom, victimKingdom);
                warKill = true;
            }
        }

        if (warKill) {
            bountyManager.applyKillRewardDuringWar(killer.getUniqueId(), victim.getUniqueId(), finalReward);
        } else {
            bountyManager.applyKillRewardWithAmount(killer.getUniqueId(), victim.getUniqueId(), finalReward);
        }

        if (warKill) {
            killer.sendMessage("§c[War] §fWar kill reward: §e" + finalReward
                    + " §7(100 base + " + bonusFromVictimBounty + " from 20% of victim bounty "
                    + victimBountyAtDeath + ", then +50% war bonus)§f.");
            victim.sendMessage("§c[War] §fYou died during an active war. Your bounty was §enot reset§f until war ends.");
        } else {
            killer.sendMessage("§b[Bounty] §fKill reward: §e" + finalReward
                    + " §7(100 base + " + bonusFromVictimBounty + " from 20% of victim bounty "
                    + victimBountyAtDeath + ")§f.");
            victim.sendMessage("§b[Bounty] §fYour bounty was reset to 0 after being killed.");
        }
    }

    private boolean isValidKill(Player killer, Player victim,
                                String killerKingdom, String victimKingdom,
                                boolean contractKill) {
        if (!contractKill && databaseManager.isKillCooldownActive(killer.getUniqueId(), victim.getUniqueId())) {
            killer.sendMessage("§b[Bounty] §cYou recently killed this player! No bounty reward.");
            return false;
        }

        if (!contractKill && killerKingdom != null && killerKingdom.equals(victimKingdom)) {
            killer.sendMessage("§b[Bounty] §cCannot earn bounty from same kingdom members!");
            return false;
        }

        if (!contractKill) {
            databaseManager.recordKillCooldown(killer.getUniqueId(), victim.getUniqueId());
        }

        return true;
    }
}
