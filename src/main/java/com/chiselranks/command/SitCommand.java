package com.chiselranks.command;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.manager.SitManager;
import com.chiselranks.rank.Rank;
import com.chiselranks.rank.RankService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SitCommand implements CommandExecutor {
    private final ChiselRanksPlugin plugin;
    private final RankService rankService;
    private final SitManager sitManager;

    public SitCommand(ChiselRanksPlugin plugin, RankService rankService, SitManager sitManager) {
        this.plugin = plugin;
        this.rankService = rankService;
        this.sitManager = sitManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }

        if (!rankService.isGoldOrHigher(player)) {
            player.sendMessage(plugin.rankRequirementMessage(Rank.GOLD));
            return true;
        }

        if (sitManager.isSitting(player)) {
            sitManager.standUp(player);
            player.sendMessage(plugin.message("messages.sit-exit"));
            return true;
        }

        if (!sitManager.sitDown(player)) {
            player.sendMessage(plugin.message("messages.sit-failed"));
            return true;
        }

        player.sendMessage(plugin.message("messages.sit-enter"));
        return true;
    }
}
