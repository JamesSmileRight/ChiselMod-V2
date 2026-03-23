package com.chiselranks.util;

import net.md_5.bungee.api.ChatColor;

import java.awt.Color;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ColorUtil {
    private static final Pattern HEX_PATTERN = Pattern.compile("#[a-fA-F0-9]{6}");

    private ColorUtil() {
    }

    public static String colorize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(ChatColor.of(hex).toString()));
        }
        matcher.appendTail(buffer);

        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    public static String gradient(String text, String startHex, String endHex) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String stripped = ChatColor.stripColor(colorize(text));
        if (stripped == null || stripped.isEmpty()) {
            return "";
        }

        Color start = parseColor(startHex, new Color(89, 184, 255));
        Color end = parseColor(endHex, new Color(155, 229, 255));

        int length = stripped.length();
        if (length == 1) {
            return ChatColor.of(start) + stripped;
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            float ratio = (float) i / (length - 1);
            int red = (int) (start.getRed() + ((end.getRed() - start.getRed()) * ratio));
            int green = (int) (start.getGreen() + ((end.getGreen() - start.getGreen()) * ratio));
            int blue = (int) (start.getBlue() + ((end.getBlue() - start.getBlue()) * ratio));

            out.append(ChatColor.of(new Color(red, green, blue))).append(stripped.charAt(i));
        }
        return out.toString();
    }

    private static Color parseColor(String hex, Color fallback) {
        if (hex == null || !HEX_PATTERN.matcher(hex).matches()) {
            return fallback;
        }
        return Color.decode(hex);
    }
}
