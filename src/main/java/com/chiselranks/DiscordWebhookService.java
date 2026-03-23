package com.chiselranks;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
public final class DiscordWebhookService {

    private final JavaPlugin plugin;
    private final HttpClient httpClient;

    public DiscordWebhookService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void sendStaffAudit(String title, String description) {
        if (!plugin.getConfig().getBoolean("discord.log-staff", true)) {
            return;
        }
        sendEmbed(title, description, 15105570);
    }

    public void sendStorePurchase(String playerName, String product, String details) {
        if (!plugin.getConfig().getBoolean("discord.log-store", true)) {
            return;
        }
        sendEmbed("Store Purchase", "**" + escapeMarkdown(playerName) + "** bought **"
                + escapeMarkdown(product) + "**\n" + escapeMarkdown(details), 5763719);
    }

    public void sendRevival(String revivedName, String source, String details) {
        if (!plugin.getConfig().getBoolean("discord.log-revival", true)) {
            return;
        }
        sendEmbed("Player Revived", "**" + escapeMarkdown(revivedName) + "** was revived via **"
                + escapeMarkdown(source) + "**\n" + escapeMarkdown(details), 15844367);
    }

    public void sendEmbed(String title, String description, int color) {
        String webhookUrl = plugin.getConfig().getString("discord.webhook-url", "");
        if (!plugin.getConfig().getBoolean("discord.enabled", false) || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        String username = plugin.getConfig().getString("discord.username", "ChiselBot");
        String avatarUrl = plugin.getConfig().getString("discord.avatar-url", "");

        String payload = "{"
                + "\"username\":\"" + escapeJson(username) + "\"," 
                + "\"avatar_url\":\"" + escapeJson(avatarUrl) + "\"," 
                + "\"embeds\":[{"
                + "\"title\":\"" + escapeJson(title) + "\"," 
                + "\"description\":\"" + escapeJson(description) + "\"," 
                + "\"color\":" + color + "}]}";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(6))
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (IOException | InterruptedException exception) {
                plugin.getLogger().warning("Failed to post Discord webhook: " + exception.getMessage());
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private String escapeMarkdown(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`")
                .replace("|", "\\|")
                .replace("<", "＜")
                .replace(">", "＞");
    }
}