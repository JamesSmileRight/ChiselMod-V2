package com.chiselranks.manager;

import java.util.Arrays;
import java.util.Optional;

public enum TrailStyle {
    FLAME("flame"),
    END_ROD("end_rod"),
    DUST("dust");

    private final String key;

    TrailStyle(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public static Optional<TrailStyle> fromInput(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        String normalized = input.trim().toLowerCase().replace('-', '_');
        return Arrays.stream(values())
                .filter(style -> style.key.equals(normalized))
                .findFirst();
    }
}
