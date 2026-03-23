package com.beyondminer.leaderboards.managers;

import com.beyondminer.leaderboards.models.LeaderboardEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public final class HologramManager {

    private static final double LINE_SPACING = 0.3D;
    private static final Method SET_COLLIDABLE_METHOD = resolveSetCollidableMethod();

    private final List<UUID> spawnedArmorStands = new ArrayList<>();
    private UUID playerTitleUuid;
    private UUID kingdomTitleUuid;

    private List<LeaderboardEntry> playerEntries = Collections.emptyList();
    private List<LeaderboardEntry> kingdomEntries = Collections.emptyList();

    public void updateData(List<LeaderboardEntry> players, List<LeaderboardEntry> kingdoms) {
        this.playerEntries = List.copyOf(players);
        this.kingdomEntries = List.copyOf(kingdoms);
    }

    public void clearHolograms() {
        Iterator<UUID> iterator = spawnedArmorStands.iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
            iterator.remove();
        }
        playerTitleUuid = null;
        kingdomTitleUuid = null;
    }

    private void spawnLeaderboard(Location baseLocation, Component title, List<LeaderboardEntry> entries) {
        spawnLeaderboard(baseLocation, title, entries, null);
    }

    private void spawnLeaderboard(Location baseLocation, Component title, List<LeaderboardEntry> entries, String key) {
        if (baseLocation == null || baseLocation.getWorld() == null) {
            return;
        }

        ArmorStand titleStand = spawnLine(baseLocation, title);
        if (titleStand != null) {
            if ("players".equals(key)) {
                playerTitleUuid = titleStand.getUniqueId();
            } else if ("kingdoms".equals(key)) {
                kingdomTitleUuid = titleStand.getUniqueId();
            }
        }

        if (entries.isEmpty()) {
            spawnLine(
                    baseLocation.clone().subtract(0.0D, LINE_SPACING, 0.0D),
                    Component.text("No data available", NamedTextColor.GRAY)
            );
            return;
        }

        for (int index = 0; index < entries.size(); index++) {
            int rank = index + 1;
            LeaderboardEntry entry = entries.get(index);
            NamedTextColor rankColor = getRankColor(rank);

            Component line = Component.text("#" + rank + " " + entry.name() + " - " + entry.bounty(), rankColor);
            Location lineLocation = baseLocation.clone().subtract(0.0D, LINE_SPACING * (index + 1), 0.0D);
            spawnLine(lineLocation, line);
        }
    }

    public void spawnPlayerLeaderboard(Location loc) {
        spawnLeaderboard(
                loc,
                Component.text("Top Players Leaderboard", NamedTextColor.AQUA, TextDecoration.BOLD),
                playerEntries,
                "players"
        );
    }

    public void spawnKingdomLeaderboard(Location loc) {
        spawnLeaderboard(
                loc,
                Component.text("Top Kingdoms Leaderboard", NamedTextColor.AQUA, TextDecoration.BOLD),
                kingdomEntries,
                "kingdoms"
        );
    }

    public void rotateTitles(Component playerTitle, Component kingdomTitle) {
        updateTitle(playerTitleUuid, playerTitle);
        updateTitle(kingdomTitleUuid, kingdomTitle);
    }

    private void updateTitle(UUID uuid, Component title) {
        if (uuid == null || title == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(uuid);
        if (entity instanceof ArmorStand armorStand && armorStand.isValid()) {
            armorStand.customName(title);
        }
    }

    private ArmorStand spawnLine(Location location, Component text) {
        if (location.getWorld() == null) {
            return null;
        }

        ArmorStand armorStand = location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setCanPickupItems(false);
            setCollidableIfSupported(stand, false);
            stand.setPersistent(false);
            stand.customName(text);
            stand.setCustomNameVisible(true);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setSmall(true);
        });

        spawnedArmorStands.add(armorStand.getUniqueId());
        return armorStand;
    }

    private NamedTextColor getRankColor(int rank) {
        if (rank == 1) {
            return NamedTextColor.GOLD;
        }
        if (rank == 2) {
            return NamedTextColor.YELLOW;
        }
        if (rank == 3) {
            return NamedTextColor.GREEN;
        }
        return NamedTextColor.GRAY;
    }

    private static Method resolveSetCollidableMethod() {
        try {
            return Entity.class.getMethod("setCollidable", boolean.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void setCollidableIfSupported(Entity entity, boolean collidable) {
        if (SET_COLLIDABLE_METHOD == null || entity == null) {
            return;
        }

        try {
            SET_COLLIDABLE_METHOD.invoke(entity, collidable);
        } catch (ReflectiveOperationException ignored) {
            // Target API does not expose setCollidable; safe to skip.
        }
    }
}
