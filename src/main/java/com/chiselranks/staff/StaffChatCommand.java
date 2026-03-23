package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class StaffChatCommand implements CommandExecutor {
    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;

    public StaffChatCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }

        if (!staffManager.isStaff(player)) {
            player.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("sctoggle")) {
            boolean enabled = staffManager.toggleStaffChat(player);
            player.sendMessage(plugin.message(enabled ? "messages.staff-chat-toggle-on" : "messages.staff-chat-toggle-off"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(plugin.message("messages.staff-chat-usage"));
            return true;
        }

        staffManager.sendStaffChat(player, String.join(" ", args));
        return true;
    }
}