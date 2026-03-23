package com.chiselranks.manager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SitManager {
    private static final Method SET_COLLIDABLE_METHOD = resolveSetCollidableMethod();

    private final Map<UUID, UUID> playerToSeat = new HashMap<>();
    private final Map<UUID, UUID> seatToPlayer = new HashMap<>();

    public boolean isSitting(Player player) {
        return playerToSeat.containsKey(player.getUniqueId());
    }

    public boolean sitDown(Player player) {
        if (isSitting(player) || player.isInsideVehicle()) {
            return false;
        }

        Location seatLocation = player.getLocation().clone().add(0.0, -1.2, 0.0);
        ArmorStand seat = player.getWorld().spawn(seatLocation, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setInvulnerable(true);
            stand.setGravity(false);
            stand.setSilent(true);
            stand.setPersistent(false);
            setCollidableIfSupported(stand, false);
            stand.setMarker(false);
            stand.setSmall(true);
            stand.setCanMove(false);
        });

        if (!seat.addPassenger(player)) {
            seat.remove();
            return false;
        }

        UUID playerId = player.getUniqueId();
        UUID seatId = seat.getUniqueId();
        playerToSeat.put(playerId, seatId);
        seatToPlayer.put(seatId, playerId);
        return true;
    }

    public void standUp(Player player) {
        UUID playerId = player.getUniqueId();
        UUID seatId = playerToSeat.remove(playerId);
        if (seatId == null) {
            return;
        }

        seatToPlayer.remove(seatId);
        Entity seat = Bukkit.getEntity(seatId);
        if (seat != null) {
            seat.removePassenger(player);
            seat.remove();
        }
    }

    public boolean isManagedSeat(Entity entity) {
        return seatToPlayer.containsKey(entity.getUniqueId());
    }

    public void removeSeatEntity(Entity entity) {
        UUID seatId = entity.getUniqueId();
        UUID playerId = seatToPlayer.remove(seatId);
        if (playerId != null) {
            playerToSeat.remove(playerId);
        }

        if (entity.isValid()) {
            entity.remove();
        }
    }

    public void clearAll() {
        for (UUID seatId : seatToPlayer.keySet()) {
            Entity entity = Bukkit.getEntity(seatId);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
        playerToSeat.clear();
        seatToPlayer.clear();
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