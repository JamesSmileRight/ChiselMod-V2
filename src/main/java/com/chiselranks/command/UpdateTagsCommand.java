package com.chiselranks.command;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.staff.NameTagManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command to refresh player name tags/prefixes from database.
 * Useful after database updates or plugin reload.
 */
public final class UpdateTagsCommand implements CommandExecutor {
    private final ChiselRanksPlugin plugin;
    private final NameTagManager nameTagManager;

    public UpdateTagsCommand(ChiselRanksPlugin plugin, NameTagManager nameTagManager) {
        this.plugin = plugin;
        this.nameTagManager = nameTagManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chiselranks.updatetags.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        sender.sendMessage("§6[UpdateTags] §eRefreshing player tags...");
        
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            nameTagManager.updatePlayer(player);
            count++;
        }

        sender.sendMessage("§6[UpdateTags] §aSuccessfully updated tags for §f" + count + "§a players.");
        return true;
    }
}
