package com.beyondminer.bounty.models;

public class KingdomBounty {
    private String kingdomName;
    private int bounty;

    public KingdomBounty(String kingdomName, int bounty) {
        this.kingdomName = kingdomName;
        this.bounty = bounty;
    }

    public String getKingdomName() {
        return kingdomName;
    }

    public int getBounty() {
        return bounty;
    }

    public void setBounty(int bounty) {
        this.bounty = bounty;
    }

    public void addBounty(int amount) {
        this.bounty += amount;
    }
}
