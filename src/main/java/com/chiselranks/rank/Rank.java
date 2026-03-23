package com.chiselranks.rank;

import java.util.Arrays;
import java.util.Optional;

public enum Rank {
    NONE("none", "None", 0),
    GOLD("gold", "Gold", 1),
    DIAMOND("diamond", "Diamond", 2),
    NETHERITE("netherite", "Netherite", 3);

    private final String key;
    private final String displayName;
    private final int weight;

    Rank(String key, String displayName, int weight) {
        this.key = key;
        this.displayName = displayName;
        this.weight = weight;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isAtLeast(Rank other) {
        return this.weight >= other.weight;
    }

    public static Optional<Rank> fromKey(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        String normalized = input.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(rank -> rank.key.equals(normalized))
                .findFirst();
    }
}
