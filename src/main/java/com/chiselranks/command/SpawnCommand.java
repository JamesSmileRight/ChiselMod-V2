package com.chiselranks.command;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SpawnCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "chiselranks.admin.spawnpoint";
    private static final long TELEPORT_DELAY_TICKS = 100L;

    private final ChiselRanksPlugin plugin;
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();

    public SpawnCommand(ChiselRanksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase();
        if ("setspawn".equals(commandName)) {
            return handleSetSpawn(sender);
        }

        return handleSpawn(sender);
    }

    private boolean handleSetSpawn(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.message("messages.spawnfly-op-only"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }

        Location location = player.getLocation();
        plugin.getConfig().set("spawn-point.world", location.getWorld() == null ? null : location.getWorld().getName());
        plugin.getConfig().set("spawn-point.x", location.getX());
        plugin.getConfig().set("spawn-point.y", location.getY());
        plugin.getConfig().set("spawn-point.z", location.getZ());
        plugin.getConfig().set("spawn-point.yaw", location.getYaw());
        plugin.getConfig().set("spawn-point.pitch", location.getPitch());
        plugin.saveConfig();

        sender.sendMessage(plugin.message("messages.setspawn-success")
                .replace("%world%", location.getWorld() == null ? "unknown" : location.getWorld().getName())
                .replace("%x%", Integer.toString(location.getBlockX()))
                .replace("%y%", Integer.toString(location.getBlockY()))
                .replace("%z%", Integer.toString(location.getBlockZ())));
        return true;
    }

    private boolean handleSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }

        Location target = getConfiguredSpawn();
        if (target == null) {
            player.sendMessage(plugin.message("messages.spawn-not-set"));
            return true;
        }

        PendingTeleport existing = pendingTeleports.remove(player.getUniqueId());
        if (existing != null) {
            existing.task.cancel();
        }

        Location start = player.getLocation().clone();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> completeTeleport(player.getUniqueId()), TELEPORT_DELAY_TICKS);
        pendingTeleports.put(player.getUniqueId(), new PendingTeleport(start, target, task));
        player.sendMessage(plugin.message("messages.spawn-teleport-start"));
        return true;
    }

    public void cancelIfMoved(Player player, Location from, Location to) {
        if (player == null || to == null) {
            return;
        }

        PendingTeleport pending = pendingTeleports.get(player.getUniqueId());
        if (pending == null) {
            return;
        }

        if (from != null && from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        pending.task.cancel();
        pendingTeleports.remove(player.getUniqueId());
        player.sendMessage(plugin.message("messages.spawn-teleport-cancelled"));
    }

    public void cancelPending(Player player) {
        if (player == null) {
            return;
        }

        PendingTeleport pending = pendingTeleports.remove(player.getUniqueId());
        if (pending != null) {
            pending.task.cancel();
        }
    }

    private void completeTeleport(UUID playerId) {
        PendingTeleport pending = pendingTeleports.remove(playerId);
        if (pending == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        Location current = player.getLocation();
        if (current.getWorld() != pending.start.getWorld()
                || current.getBlockX() != pending.start.getBlockX()
                || current.getBlockY() != pending.start.getBlockY()
                || current.getBlockZ() != pending.start.getBlockZ()) {
            player.sendMessage(plugin.message("messages.spawn-teleport-cancelled"));
            return;
        }

        player.teleport(pending.target);
        player.sendMessage(plugin.message("messages.spawn-teleport-success"));
    }

    private Location getConfiguredSpawn() {
        String worldName = plugin.getConfig().getString("spawn-point.world");
        if (worldName == null || worldName.isBlank()) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        return new Location(
                world,
                plugin.getConfig().getDouble("spawn-point.x"),
                plugin.getConfig().getDouble("spawn-point.y"),
                plugin.getConfig().getDouble("spawn-point.z"),
                (float) plugin.getConfig().getDouble("spawn-point.yaw"),
                (float) plugin.getConfig().getDouble("spawn-point.pitch")
        );
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private record PendingTeleport(Location start, Location target, BukkitTask task) {
    }
}