package com.chisellives;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.regex.Pattern;

public final class BanManager {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final JavaPlugin plugin;

    public BanManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void banPlayer(UUID uuid, String username, String reason) {
        runSync(() -> {
            String safeUsername = sanitizeUsername(username);
            String safeReason = sanitizeReason(reason);
            if (safeUsername != null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + safeUsername + " " + safeReason);
            }

            Player onlinePlayer = Bukkit.getPlayer(uuid);
            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                onlinePlayer.kick(Component.text(safeReason));
            }
        });
    }

    public void unbanPlayer(UUID uuid, String username) {
        runSync(() -> {
            String safeUsername = sanitizeUsername(username);
            if (safeUsername != null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + safeUsername);
            }
        });
    }

    private String sanitizeUsername(String username) {
        if (username == null) {
            return null;
        }

        String trimmed = username.trim();
        if (!USERNAME_PATTERN.matcher(trimmed).matches()) {
            return null;
        }

        return trimmed;
    }

    private String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "You ran out of lives.";
        }

        return reason.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private void runSync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }

        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
