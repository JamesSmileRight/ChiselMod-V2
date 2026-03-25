package com.chiselranks.staff;

import com.beyondminer.kingdoms.managers.KingdomManager;
import com.beyondminer.kingdoms.models.Kingdom;
import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.RankManager;
import com.chiselranks.rank.Rank;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class NameTagManager {
    private final ChiselRanksPlugin plugin;
    private final RankManager rankManager;
    private final StaffManager staffManager;
    private KingdomManager kingdomManager;

    public NameTagManager(ChiselRanksPlugin plugin, RankManager rankManager, StaffManager staffManager) {
        this.plugin = plugin;
        this.rankManager = rankManager;
        this.staffManager = staffManager;
        loadKingdomManager();
    }

    /**
     * Lazily loads KingdomManager from the bounty leaderboards plugin.
     */
    private void loadKingdomManager() {
        try {
            Plugin bountyPlugin = Bukkit.getPluginManager().getPlugin("KingdomsBounty");
            if (bountyPlugin instanceof com.beyondminer.leaderboards.BountyLeaderboards) {
                // Access via reflection or helper method if available
                // For now, store null and try to get it when needed
            }
        } catch (Exception ignored) {
            // Plugin not loaded or not accessible
        }
    }

    /**
     * Sets the kingdom manager (called after initialization).
     */
    public void setKingdomManager(KingdomManager kingdomManager) {
        this.kingdomManager = kingdomManager;
    }

    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
        }
    }

    public void updatePlayer(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager() == null
                ? null
                : Bukkit.getScoreboardManager().getMainScoreboard();
        if (scoreboard == null) {
            return;
        }

        for (Team team : scoreboard.getTeams()) {
            team.removeEntry(player.getName());
        }

        String key = resolveTeamKey(player);
        Team team = scoreboard.getTeam("role_" + key);
        if (team == null) {
            team = scoreboard.registerNewTeam("role_" + key);
        }

        String prefix = resolvePrefix(player);
        if (!prefix.equals(team.getPrefix())) {
            team.setPrefix(prefix);
        }
        team.addEntry(player.getName());
    }

    /**
     * Resolves all applicable tags for nametag/chat display.
     * Returns: [STAFF] [RANK] [KINGDOM] format for players with multiple tags
     *          [STAFF] for staff members (overrides rank)
     *          [RANK] for ranked players without staff role
     *          [KINGDOM] for kingdom members without staff/rank
     *          "" for players with no tags
     */
    public String resolvePrefix(Player player) {
        StringBuilder prefix = new StringBuilder();

        // Add staff role if applicable (highest priority)
        StaffRole staffRole = staffManager.getRole(player);
        if (staffRole != StaffRole.NONE) {
            prefix.append(plugin.message("chat.staff-prefix." + staffRole.getKey())).append("\n");
        }

        // Add player rank
        Rank rank = rankManager.getRank(player);
        if (rank != Rank.NONE) {
            prefix.append(plugin.message("chat.prefix." + rank.getKey())).append("\n");
        }

        // Add kingdom tag if player is in a kingdom
        if (kingdomManager != null) {
            try {
                Kingdom kingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());
                if (kingdom != null) {
                    prefix.append("&f[&7").append(kingdom.getName()).append("&f]&r").append("\n");
                }
            } catch (Exception ignored) {
                // Kingdom manager not available or error retrieving kingdom
            }
        }

        // Remove trailing newline
        if (prefix.length() > 0 && prefix.charAt(prefix.length() - 1) == '\n') {
            prefix.setLength(prefix.length() - 1);
        }

        return prefix.toString();
    }

    private String resolveTeamKey(Player player) {
        StaffRole staffRole = staffManager.getRole(player);
        if (staffRole != StaffRole.NONE) {
            return staffRole.getKey();
        }

        Rank rank = rankManager.getRank(player);
        return rank == Rank.NONE ? "unranked" : rank.getKey();
    }
}