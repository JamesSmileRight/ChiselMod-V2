package com.beyondminer.bounty.models;

import java.util.UUID;

public class PlayerBounty {
    private UUID playerUuid;
    private int bounty;
    private int kills;
    private int deaths;

    public PlayerBounty(UUID playerUuid, int bounty, int kills, int deaths) {
        this.playerUuid = playerUuid;
        this.bounty = bounty;
        this.kills = kills;
        this.deaths = deaths;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public int getBounty() {
        return bounty;
    }

    public void setBounty(int bounty) {
        this.bounty = bounty;
    }

    public int getKills() {
        return kills;
    }

    public void setKills(int kills) {
        this.kills = kills;
    }

    public int getDeaths() {
        return deaths;
    }

    public void setDeaths(int deaths) {
        this.deaths = deaths;
    }

    public void addBounty(int amount) {
        this.bounty += amount;
    }

    public void resetBounty() {
        this.bounty = 0;
    }

    public void incrementKills() {
        this.kills++;
    }

    public void incrementDeaths() {
        this.deaths++;
    }
}
