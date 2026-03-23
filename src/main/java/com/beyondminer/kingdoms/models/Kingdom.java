package com.beyondminer.kingdoms.models;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a Kingdom with a leader and members.
 */
public class Kingdom {
    private final String name;
    private final UUID leader;
    private final String color;
    private final long createdAt;
    private final Set<UUID> members;
    private String capitalWorld;
    private double capitalX;
    private double capitalY;
    private double capitalZ;
    private float capitalYaw;
    private float capitalPitch;

    public Kingdom(String name, UUID leader) {
        this(name, leader, "gold", System.currentTimeMillis());
    }

    public Kingdom(String name, UUID leader, String color, long createdAt) {
        this.name = name;
        this.leader = leader;
        this.color = color;
        this.createdAt = createdAt;
        this.members = ConcurrentHashMap.newKeySet();
        this.members.add(leader);
    }

    public Kingdom(String name, UUID leader, String color, long createdAt,
                   String capitalWorld, double capitalX, double capitalY, double capitalZ,
                   float capitalYaw, float capitalPitch) {
        this(name, leader, color, createdAt);
        this.capitalWorld = capitalWorld;
        this.capitalX = capitalX;
        this.capitalY = capitalY;
        this.capitalZ = capitalZ;
        this.capitalYaw = capitalYaw;
        this.capitalPitch = capitalPitch;
    }

    public String getName() {
        return name;
    }

    public UUID getLeader() {
        return leader;
    }

    public String getColor() {
        return color;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public Set<UUID> getMembers() {
        return new HashSet<>(members);
    }

    public void addMember(UUID playerUUID) {
        members.add(playerUUID);
    }

    public void removeMember(UUID playerUUID) {
        members.remove(playerUUID);
    }

    public boolean isLeader(UUID playerUUID) {
        return leader.equals(playerUUID);
    }

    public boolean isMember(UUID playerUUID) {
        return members.contains(playerUUID);
    }

    public int getMemberCount() {
        return members.size();
    }

    public boolean hasCapital() {
        return capitalWorld != null && !capitalWorld.isBlank();
    }

    public Location getCapitalLocation() {
        if (!hasCapital()) {
            return null;
        }

        World world = Bukkit.getWorld(capitalWorld);
        if (world == null) {
            return null;
        }

        return new Location(world, capitalX, capitalY, capitalZ, capitalYaw, capitalPitch);
    }

    public String getCapitalWorld() {
        return capitalWorld;
    }

    public double getCapitalX() {
        return capitalX;
    }

    public double getCapitalY() {
        return capitalY;
    }

    public double getCapitalZ() {
        return capitalZ;
    }

    public float getCapitalYaw() {
        return capitalYaw;
    }

    public float getCapitalPitch() {
        return capitalPitch;
    }

    public void setCapital(Location capital) {
        if (capital == null || capital.getWorld() == null) {
            capitalWorld = null;
            capitalX = 0.0D;
            capitalY = 0.0D;
            capitalZ = 0.0D;
            capitalYaw = 0.0F;
            capitalPitch = 0.0F;
            return;
        }

        capitalWorld = capital.getWorld().getName();
        capitalX = capital.getX();
        capitalY = capital.getY();
        capitalZ = capital.getZ();
        capitalYaw = capital.getYaw();
        capitalPitch = capital.getPitch();
    }
}
