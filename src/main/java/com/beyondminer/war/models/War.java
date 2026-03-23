package com.beyondminer.war.models;

public class War {
    private final String kingdomA;
    private final String kingdomB;
    private final long startTime;
    private final boolean active;

    public War(String kingdomA, String kingdomB, long startTime, boolean active) {
        this.kingdomA = kingdomA;
        this.kingdomB = kingdomB;
        this.startTime = startTime;
        this.active = active;
    }

    public String getKingdomA() { return kingdomA; }
    public String getKingdomB() { return kingdomB; }
    public long getStartTime()  { return startTime; }
    public boolean isActive()   { return active; }

    public boolean involves(String kingdom) {
        return kingdomA.equalsIgnoreCase(kingdom) || kingdomB.equalsIgnoreCase(kingdom);
    }

    public String getOpponent(String kingdom) {
        if (kingdomA.equalsIgnoreCase(kingdom)) return kingdomB;
        if (kingdomB.equalsIgnoreCase(kingdom)) return kingdomA;
        return null;
    }
}
