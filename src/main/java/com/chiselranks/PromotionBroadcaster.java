package com.chiselranks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class PromotionBroadcaster {
    private final ChiselRanksPlugin plugin;
    private BukkitTask task;

    public PromotionBroadcaster(ChiselRanksPlugin plugin) {
        this.plugin = plugin;
    }

    public void restart() {
        stop();
        if (!plugin.getConfig().getBoolean("promotion.enabled", false)) {
            return;
        }

        long intervalMinutes = Math.max(1L, plugin.getConfig().getLong("promotion.interval-minutes", 20L));
        long intervalTicks = intervalMinutes * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::broadcastRandomPromotion, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void broadcastRandomPromotion() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }

        PromotionType[] values = PromotionType.values();
        PromotionType type = values[ThreadLocalRandom.current().nextInt(values.length)];
        String storeUrl = switch (type) {
            case RANKS -> plugin.getStoreUrl();
            case LIVES, TOTEM -> normalizeUrl(plugin.getConfig().getString("chisel-lives.store-url", plugin.getStoreUrl()));
        };
        Title title = Title.title(
                Component.text(type.title(), type.color()),
                Component.text(type.subtitle(), NamedTextColor.WHITE),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(350))
        );
        Component link = Component.text(storeUrl, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(storeUrl))
                .hoverEvent(HoverEvent.showText(Component.text("Open store", NamedTextColor.GRAY)));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8F, 1.05F);
            player.sendMessage(Component.text("================================", NamedTextColor.DARK_GRAY));
            player.sendMessage(Component.text("[Store] ", NamedTextColor.GOLD).append(Component.text(type.title(), type.color())));
            for (String line : type.lines()) {
                player.sendMessage(Component.text(line, NamedTextColor.WHITE));
            }
            player.sendMessage(Component.text("Open store: ", NamedTextColor.YELLOW).append(link));
            player.sendMessage(Component.text("================================", NamedTextColor.DARK_GRAY));
        }
    }

    private String normalizeUrl(String value) {
        if (value == null || value.isBlank()) {
            return plugin.getStoreUrl();
        }
        return value.startsWith("http://") || value.startsWith("https://") ? value : "https://" + value;
    }

    private enum PromotionType {
        RANKS(
                "Upgrade Your Rank",
                "Gold, Diamond, and Netherite perks live now",
                NamedTextColor.GOLD,
                List.of(
                        "Unlock colored chat, custom join messages, and portable utility commands.",
                        "Diamond adds stronger tools, more homes, and cleaner flex value.",
                        "Netherite adds a death message, dark trail, emoji chat, and max homes."
                )
        ),
        LIVES(
                "Need More Lives?",
                "Buy extra lives up to 15 total",
                NamedTextColor.RED,
                List.of(
                        "Stay in the fight longer instead of risking elimination after one bad streak.",
                        "Extra lives protect your progress when PvP, wars, or bounties get rough.",
                        "Top yourself up before the next big fight."
                )
        ),
        TOTEM(
                "Save A Friend",
                "Revival Totems can bring a teammate back",
                NamedTextColor.LIGHT_PURPLE,
                List.of(
                        "A Revival Totem lets you rescue a banned or eliminated teammate.",
                        "Bring a friend back into the world when your kingdom needs them most.",
                        "Keep one ready for the clutch recovery play."
                )
        );

        private final String title;
        private final String subtitle;
        private final NamedTextColor color;
        private final List<String> lines;

        PromotionType(String title, String subtitle, NamedTextColor color, List<String> lines) {
            this.title = title;
            this.subtitle = subtitle;
            this.color = color;
            this.lines = lines;
        }

        private String title() {
            return title;
        }

        private String subtitle() {
            return subtitle;
        }

        private NamedTextColor color() {
            return color;
        }

        private List<String> lines() {
            return lines;
        }
    }
}