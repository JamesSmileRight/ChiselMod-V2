package com.chiselranks.home;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.DatabaseManager;
import com.chiselranks.RankManager;
import com.chiselranks.rank.Rank;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class HomeManager {
    private static final long TELEPORT_COOLDOWN_MILLIS = 3000L;

    private final ChiselRanksPlugin plugin;
    private final DatabaseManager databaseManager;
    private final RankManager rankManager;
    private final Map<UUID, Map<String, DatabaseManager.HomeRecord>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public HomeManager(ChiselRanksPlugin plugin, DatabaseManager databaseManager, RankManager rankManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.rankManager = rankManager;
    }

    public void loadPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        databaseManager.loadHomes(uuid).thenAccept(homes -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            cache.put(uuid, new ConcurrentHashMap<>(homes));
        }));
    }

    public void unloadPlayer(Player player) {
        cache.remove(player.getUniqueId());
        cooldowns.remove(player.getUniqueId());
    }

    public int getHomeLimit(Player player) {
        Rank rank = rankManager.getRank(player);
        if (rank.isAtLeast(Rank.NETHERITE)) {
            return 5;
        }
        if (rank.isAtLeast(Rank.DIAMOND)) {
            return 3;
        }
        if (rank.isAtLeast(Rank.GOLD)) {
            return 1;
        }
        return 0;
    }

    public CompletableFuture<HomeResult> setHome(Player player, String homeName) {
        String normalizedName = normalizeHomeName(homeName);
        int limit = getHomeLimit(player);
        if (limit <= 0) {
            return CompletableFuture.completedFuture(HomeResult.failure("You do not have access to homes."));
        }

        Map<String, DatabaseManager.HomeRecord> homes = cache.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        if (!homes.containsKey(normalizedName) && homes.size() >= limit) {
            return CompletableFuture.completedFuture(HomeResult.failure("You have reached your home limit of " + limit + '.'));
        }

        Location location = player.getLocation().clone();
        return databaseManager.saveHome(player.getUniqueId(), normalizedName, location)
                .thenApply(ignored -> {
                    homes.put(normalizedName, new DatabaseManager.HomeRecord(
                            normalizedName,
                            location.getWorld() == null ? "world" : location.getWorld().getName(),
                            location.getX(),
                            location.getY(),
                            location.getZ(),
                            location.getYaw(),
                            location.getPitch()
                    ));
                    return HomeResult.success("Home '&f" + normalizedName + "&a' saved.");
                });
    }

    public CompletableFuture<HomeResult> deleteHome(Player player, String homeName) {
        String normalizedName = normalizeHomeName(homeName);
        return databaseManager.deleteHome(player.getUniqueId(), normalizedName)
                .thenApply(deleted -> {
                    if (!deleted) {
                        return HomeResult.failure("That home does not exist.");
                    }

                    Map<String, DatabaseManager.HomeRecord> homes = cache.get(player.getUniqueId());
                    if (homes != null) {
                        homes.remove(normalizedName);
                    }
                    return HomeResult.success("Home '&f" + normalizedName + "&a' deleted.");
                });
    }

    public HomeResult teleportHome(Player player, String homeName) {
        if (isOnCooldown(player)) {
            return HomeResult.failure("Wait a moment before using /home again.");
        }

        Map<String, DatabaseManager.HomeRecord> homes = cache.get(player.getUniqueId());
        if (homes == null || homes.isEmpty()) {
            return HomeResult.failure("You do not have any homes set.");
        }

        DatabaseManager.HomeRecord record = homes.get(normalizeHomeName(homeName));
        if (record == null) {
            return HomeResult.failure("That home does not exist.");
        }

        Location location = record.toLocation();
        if (location == null) {
            return HomeResult.failure("That home's world is missing.");
        }

        Location safeLocation = findSafeLocation(location);
        if (safeLocation == null) {
            return HomeResult.failure("That home is not safe to teleport to.");
        }

        player.teleport(safeLocation);
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        return HomeResult.success("Teleported to '&f" + record.name() + "&a'.");
    }

    private boolean isOnCooldown(Player player) {
        Long lastUse = cooldowns.get(player.getUniqueId());
        return lastUse != null && System.currentTimeMillis() - lastUse < TELEPORT_COOLDOWN_MILLIS;
    }

    private Location findSafeLocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }

        Location base = location.clone();
        Material feet = world.getBlockAt(base).getType();
        Material head = world.getBlockAt(base.clone().add(0.0, 1.0, 0.0)).getType();
        Material ground = world.getBlockAt(base.clone().add(0.0, -1.0, 0.0)).getType();

        if (feet.isAir() && head.isAir() && ground.isSolid()) {
            return base;
        }

        for (int offset = 1; offset <= 4; offset++) {
            Location candidate = base.clone().add(0.0, offset, 0.0);
            Material candidateFeet = world.getBlockAt(candidate).getType();
            Material candidateHead = world.getBlockAt(candidate.clone().add(0.0, 1.0, 0.0)).getType();
            Material candidateGround = world.getBlockAt(candidate.clone().add(0.0, -1.0, 0.0)).getType();
            if (candidateFeet.isAir() && candidateHead.isAir() && candidateGround.isSolid()) {
                return candidate;
            }
        }

        return null;
    }

    private String normalizeHomeName(String homeName) {
        if (homeName == null || homeName.isBlank()) {
            return "home";
        }

        String trimmed = homeName.trim().toLowerCase();
        return trimmed.length() <= 32 ? trimmed : trimmed.substring(0, 32);
    }

    public record HomeResult(boolean success, String message) {
        public static HomeResult success(String message) {
            return new HomeResult(true, message);
        }

        public static HomeResult failure(String message) {
            return new HomeResult(false, message);
        }
    }
}