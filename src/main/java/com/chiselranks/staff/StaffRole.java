package com.chiselranks.staff;

import java.util.Arrays;
import java.util.Optional;

public enum StaffRole {
    NONE("none", "", 0),
    HELPER("helper", "Helper", 1),
    MOD("mod", "Mod", 2),
    SRMOD("srmod", "SrMod", 3),
    ADMIN("admin", "Admin", 4),
    OWNER("owner", "Owner", 5);

    private final String key;
    private final String displayName;
    private final int weight;

    StaffRole(String key, String displayName, int weight) {
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

    public boolean isAtLeast(StaffRole other) {
        return this.weight >= other.weight;
    }

    public static Optional<StaffRole> fromKey(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        String normalized = input.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(role -> role.key.equals(normalized))
                .findFirst();
    }
}