package com.beyondminer.assassination.listeners;

import com.beyondminer.assassination.managers.AssassinationManager;
import com.beyondminer.assassination.models.AssassinationContract;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Rewards the killer with assassination contract bounty when a contracted target dies.
 */
public class AssassinationListener implements Listener {

    private final AssassinationManager assassinationManager;

    public AssassinationListener(AssassinationManager assassinationManager) {
        this.assassinationManager = assassinationManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        AssassinationManager.HitKillResult result = assassinationManager.handleContractKill(
                killer.getUniqueId(),
                victim.getUniqueId()
        );
        if (result == null) {
            return;
        }

        AssassinationContract contract = result.contract();
        killer.sendMessage("§d[Hit] §fContract complete! You earned §e" + contract.getAmount()
            + " §fhit bounty §7(+ normal kill bounty reward).§f");
        victim.sendMessage("§d[Hit] §cYou were eliminated by the contracted hitman.");

        Player requester = Bukkit.getPlayer(contract.getRequesterUuid());
        if (requester != null) {
            requester.sendMessage("§d[Hit] §fYour hit request on §a" + victim.getName() + " §fwas completed by §e"
                    + killer.getName() + "§f.");
        }
    }
}
