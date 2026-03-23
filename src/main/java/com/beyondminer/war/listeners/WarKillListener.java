package com.beyondminer.war.listeners;

import com.beyondminer.kingdoms.managers.KingdomManager;
import com.beyondminer.kingdoms.models.Kingdom;
import com.beyondminer.war.managers.WarManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Records kills that occur while two kingdoms are at war.
 * The bounty reward bonus itself is applied in PlayerKillListener via WarManager.
 */
public class WarKillListener implements Listener {

    private final WarManager warManager;
    private final KingdomManager kingdomManager;

    public WarKillListener(WarManager warManager, KingdomManager kingdomManager) {
        this.warManager = warManager;
        this.kingdomManager = kingdomManager;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        Kingdom killerKingdom = kingdomManager.getPlayerKingdom(killer.getUniqueId());
        Kingdom victimKingdom = kingdomManager.getPlayerKingdom(victim.getUniqueId());
        if (killerKingdom == null || victimKingdom == null) return;

        if (!warManager.areAtWar(killerKingdom.getName(), victimKingdom.getName())) return;

        warManager.recordWarKill(
                killer.getUniqueId(), victim.getUniqueId(),
                killerKingdom.getName(), victimKingdom.getName()
        );

        if (!victim.getUniqueId().equals(victimKingdom.getLeader())) {
            return;
        }

        WarManager.LeaderKillEndResult endResult = warManager.endWarByLeaderKill(
                victimKingdom.getName(),
                killerKingdom.getName()
        );
        if (endResult == null || !endResult.ended()) {
            return;
        }

        Bukkit.broadcast(Component.text(
                "§6[War] §e" + endResult.winnerKingdom() + " §fwon the war against §c" + endResult.loserKingdom()
                        + " §fbecause their leader was slain! Each winner received §e" + endResult.memberReward()
                        + " §fbounty points and §e" + endResult.kingdomVictoryReward()
                        + " §fkingdom bounty was added as victory reward."
        ));

        Kingdom winnerKingdom = kingdomManager.getKingdom(endResult.winnerKingdom());
        if (winnerKingdom == null) {
            return;
        }

        for (java.util.UUID memberUuid : winnerKingdom.getMembers()) {
            Player member = Bukkit.getPlayer(memberUuid);
            if (member != null) {
                member.sendMessage("§6[War] §fVictory reward: §e+" + endResult.memberReward()
                        + " §fbounty points.");
            }
        }
    }
}
