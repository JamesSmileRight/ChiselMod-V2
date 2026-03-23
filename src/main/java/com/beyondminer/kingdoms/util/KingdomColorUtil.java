package com.beyondminer.kingdoms.util;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class KingdomColorUtil {

    private static final Map<String, TextColor> NAMED_COLORS = createNamedColors();

    private KingdomColorUtil() {
    }

    public static String normalize(String rawColor) {
        if (rawColor == null || rawColor.isBlank()) {
            return "gold";
        }

        String normalized = rawColor.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("#") && normalized.length() == 7 && TextColor.fromHexString(normalized) != null) {
            return normalized;
        }

        return NAMED_COLORS.containsKey(normalized) ? normalized : "gold";
    }

    public static boolean isValid(String rawColor) {
        if (rawColor == null || rawColor.isBlank()) {
            return false;
        }

        String normalized = rawColor.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("#") && normalized.length() == 7) {
            return TextColor.fromHexString(normalized) != null;
        }

        return NAMED_COLORS.containsKey(normalized);
    }

    public static TextColor toTextColor(String rawColor) {
        String normalized = normalize(rawColor);
        if (normalized.startsWith("#")) {
            TextColor hexColor = TextColor.fromHexString(normalized);
            if (hexColor != null) {
                return hexColor;
            }
        }

        return NAMED_COLORS.getOrDefault(normalized, NamedTextColor.GOLD);
    }

    public static List<String> suggestions() {
        return List.copyOf(NAMED_COLORS.keySet());
    }

    private static Map<String, TextColor> createNamedColors() {
        Map<String, TextColor> colors = new LinkedHashMap<>();
        colors.put("black", NamedTextColor.BLACK);
        colors.put("dark_blue", NamedTextColor.DARK_BLUE);
        colors.put("dark_green", NamedTextColor.DARK_GREEN);
        colors.put("dark_aqua", NamedTextColor.DARK_AQUA);
        colors.put("dark_red", NamedTextColor.DARK_RED);
        colors.put("dark_purple", NamedTextColor.DARK_PURPLE);
        colors.put("gold", NamedTextColor.GOLD);
        colors.put("gray", NamedTextColor.GRAY);
        colors.put("dark_gray", NamedTextColor.DARK_GRAY);
        colors.put("blue", NamedTextColor.BLUE);
        colors.put("green", NamedTextColor.GREEN);
        colors.put("aqua", NamedTextColor.AQUA);
        colors.put("red", NamedTextColor.RED);
        colors.put("light_purple", NamedTextColor.LIGHT_PURPLE);
        colors.put("yellow", NamedTextColor.YELLOW);
        colors.put("white", NamedTextColor.WHITE);
        colors.put("purple", NamedTextColor.LIGHT_PURPLE);
        return colors;
    }
}
