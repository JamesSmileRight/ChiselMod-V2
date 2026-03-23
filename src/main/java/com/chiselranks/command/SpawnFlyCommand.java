package com.chiselranks.command;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.listener.SpawnFlightListener;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SpawnFlyCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN_PERMISSION = "chiselranks.admin.spawnregion";

    private final ChiselRanksPlugin plugin;
    private final SpawnFlightListener spawnFlightListener;
    private final Map<UUID, Location> firstSelection = new HashMap<>();
    private final Map<UUID, Location> secondSelection = new HashMap<>();

    public SpawnFlyCommand(ChiselRanksPlugin plugin, SpawnFlightListener spawnFlightListener) {
        this.plugin = plugin;
        this.spawnFlightListener = spawnFlightListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.message("messages.spawnfly-op-only"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(plugin.message("messages.spawnfly-usage"));
            return true;
        }

        String option = args[0].toLowerCase(Locale.ROOT);
        switch (option) {
            case "pos1" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.message("messages.player-only"));
                    return true;
                }
                Location location = blockLocation(player.getLocation());
                firstSelection.put(player.getUniqueId(), location);
                sender.sendMessage(formatPoint("messages.spawnfly-pos1-set", location));
                return true;
            }
            case "pos2" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.message("messages.player-only"));
                    return true;
                }
                Location location = blockLocation(player.getLocation());
                secondSelection.put(player.getUniqueId(), location);
                sender.sendMessage(formatPoint("messages.spawnfly-pos2-set", location));
                return true;
            }
            case "set", "save" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.message("messages.player-only"));
                    return true;
                }

                UUID uuid = player.getUniqueId();
                Location first = firstSelection.get(uuid);
                Location second = secondSelection.get(uuid);
                if (first == null || second == null) {
                    sender.sendMessage(plugin.message("messages.spawnfly-selection-missing"));
                    return true;
                }

                if (first.getWorld() == null || second.getWorld() == null
                        || !first.getWorld().getName().equals(second.getWorld().getName())) {
                    sender.sendMessage(plugin.message("messages.spawnfly-world-mismatch"));
                    return true;
                }

                int minX = Math.min(first.getBlockX(), second.getBlockX());
                int minY = Math.min(first.getBlockY(), second.getBlockY());
                int minZ = Math.min(first.getBlockZ(), second.getBlockZ());

                int maxX = Math.max(first.getBlockX(), second.getBlockX());
                int maxY = Math.max(first.getBlockY(), second.getBlockY());
                int maxZ = Math.max(first.getBlockZ(), second.getBlockZ());

                String world = first.getWorld().getName();
                plugin.getConfig().set("spawn-flight.enabled", true);
                plugin.getConfig().set("spawn-flight.world", world);
                plugin.getConfig().set("spawn-flight.min.x", minX);
                plugin.getConfig().set("spawn-flight.min.y", minY);
                plugin.getConfig().set("spawn-flight.min.z", minZ);
                plugin.getConfig().set("spawn-flight.max.x", maxX);
                plugin.getConfig().set("spawn-flight.max.y", maxY);
                plugin.getConfig().set("spawn-flight.max.z", maxZ);
                plugin.saveConfig();

                spawnFlightListener.reloadRegion();
                spawnFlightListener.refreshAllOnline();

                sender.sendMessage(plugin.message("messages.spawnfly-region-saved")
                        .replace("%world%", world)
                        .replace("%min%", minX + "," + minY + "," + minZ)
                        .replace("%max%", maxX + "," + maxY + "," + maxZ));
                return true;
            }
            case "clear" -> {
                plugin.getConfig().set("spawn-flight.enabled", false);
                plugin.saveConfig();

                spawnFlightListener.reloadRegion();
                spawnFlightListener.refreshAllOnline();

                sender.sendMessage(plugin.message("messages.spawnfly-cleared"));
                return true;
            }
            case "info" -> {
                boolean enabled = plugin.getConfig().getBoolean("spawn-flight.enabled", false);
                if (!enabled) {
                    sender.sendMessage(plugin.message("messages.spawnfly-info-disabled"));
                    return true;
                }

                String world = plugin.getConfig().getString("spawn-flight.world", "world");
                int minX = plugin.getConfig().getInt("spawn-flight.min.x");
                int minY = plugin.getConfig().getInt("spawn-flight.min.y");
                int minZ = plugin.getConfig().getInt("spawn-flight.min.z");

                int maxX = plugin.getConfig().getInt("spawn-flight.max.x");
                int maxY = plugin.getConfig().getInt("spawn-flight.max.y");
                int maxZ = plugin.getConfig().getInt("spawn-flight.max.z");

                sender.sendMessage(plugin.message("messages.spawnfly-info")
                        .replace("%world%", world)
                        .replace("%min%", minX + "," + minY + "," + minZ)
                        .replace("%max%", maxX + "," + maxY + "," + maxZ));
                return true;
            }
            default -> {
                sender.sendMessage(plugin.message("messages.spawnfly-usage"));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> options = List.of("pos1", "pos2", "set", "clear", "info");
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(input)) {
                out.add(option);
            }
        }
        return out;
    }

    private String formatPoint(String messagePath, Location location) {
        return plugin.message(messagePath)
                .replace("%world%", location.getWorld() == null ? "unknown" : location.getWorld().getName())
                .replace("%x%", Integer.toString(location.getBlockX()))
                .replace("%y%", Integer.toString(location.getBlockY()))
                .replace("%z%", Integer.toString(location.getBlockZ()));
    }

    private Location blockLocation(Location source) {
        return new Location(
                source.getWorld(),
                source.getBlockX(),
                source.getBlockY(),
                source.getBlockZ()
        );
    }
}