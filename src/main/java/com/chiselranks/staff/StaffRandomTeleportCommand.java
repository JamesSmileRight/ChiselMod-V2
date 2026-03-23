package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class StaffRandomTeleportCommand implements CommandExecutor {
    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;

    public StaffRandomTeleportCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }
        if (!staffManager.canUseTeleportCommands(player)) {
            player.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }

        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) {
                continue;
            }
            if (staffManager.getRole(online).isAtLeast(StaffRole.HELPER)) {
                continue;
            }
            candidates.add(online);
        }

        if (candidates.isEmpty()) {
            player.sendMessage("§cNo eligible online players were found for random staff teleport.");
            return true;
        }

        Player target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        staffManager.rememberBackLocation(player, player.getLocation());
        player.teleport(target.getLocation());
        staffManager.logAudit(StaffManager.AuditType.STAFF_MODE, player.getName(), target.getName(), "Random investigation teleport");
        player.sendMessage("§aRandomly teleported to §f" + target.getName() + "§a.");
        return true;
    }
}