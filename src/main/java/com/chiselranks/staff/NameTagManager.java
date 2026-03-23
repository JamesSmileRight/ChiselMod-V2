package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.RankManager;
import com.chiselranks.rank.Rank;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class NameTagManager {
    private final ChiselRanksPlugin plugin;
    private final RankManager rankManager;
    private final StaffManager staffManager;

    public NameTagManager(ChiselRanksPlugin plugin, RankManager rankManager, StaffManager staffManager) {
        this.plugin = plugin;
        this.rankManager = rankManager;
        this.staffManager = staffManager;
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

    public String resolvePrefix(Player player) {
        StaffRole staffRole = staffManager.getRole(player);
        if (staffRole != StaffRole.NONE) {
            return plugin.message("chat.staff-prefix." + staffRole.getKey());
        }

        Rank rank = rankManager.getRank(player);
        if (rank == Rank.NONE) {
            return "";
        }
        return plugin.message("chat.prefix." + rank.getKey());
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