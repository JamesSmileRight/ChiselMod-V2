package com.chiselranks;

import com.chiselranks.rank.Rank;
import com.chiselranks.staff.NameTagManager;
import com.chiselranks.staff.StaffManager;
import com.chiselranks.util.ColorUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerListener implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String JOIN_MESSAGE_PERMISSION = "chiselranks.perk.message.join";
    private static final String LEAVE_MESSAGE_PERMISSION = "chiselranks.perk.message.leave";
    private static final String DEATH_MESSAGE_PERMISSION = "chiselranks.perk.message.death";
    private static final String NETHERITE_TRAIL_PERMISSION = "chiselranks.perk.trail.netherite";
    private static final long TRAIL_COOLDOWN_MILLIS = 125L;
    private static final Particle.DustOptions NETHERITE_TRAIL_DUST = new Particle.DustOptions(Color.fromRGB(62, 68, 78), 1.0F);

    private final ChiselRanksPlugin plugin;
    private final RankManager rankManager;
    private final TabManager tabManager;
    private final StaffManager staffManager;
    private final NameTagManager nameTagManager;
    private final Map<UUID, Long> lastTrailAt = new ConcurrentHashMap<>();

    public PlayerListener(
            ChiselRanksPlugin plugin,
            RankManager rankManager,
            TabManager tabManager,
            StaffManager staffManager,
            NameTagManager nameTagManager
    ) {
        this.plugin = plugin;
        this.rankManager = rankManager;
        this.tabManager = tabManager;
        this.staffManager = staffManager;
        this.nameTagManager = nameTagManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncLogin(AsyncPlayerPreLoginEvent event) {
        rankManager.preloadRank(event.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        rankManager.loadPlayer(player);
        tabManager.updatePlayer(player);
        nameTagManager.updatePlayer(player);

        Rank rank = rankManager.getRank(player);
        if (!player.hasPermission(JOIN_MESSAGE_PERMISSION)) {
            return;
        }

        String template = plugin.getConfig().getString("join-messages." + rank.getKey());
        if (template != null && !template.isBlank()) {
            event.joinMessage(colorTemplate(template, player));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Rank rank = rankManager.getRank(player);
        if (plugin.getHomeManager() != null) {
            plugin.getHomeManager().unloadPlayer(player);
        }
        if (staffManager.isVanished(player)) {
            event.quitMessage(null);
            lastTrailAt.remove(player.getUniqueId());
            rankManager.unloadPlayer(player);
            return;
        }
        if (!player.hasPermission(LEAVE_MESSAGE_PERMISSION)) {
            lastTrailAt.remove(player.getUniqueId());
            rankManager.unloadPlayer(player);
            return;
        }

        String template = plugin.getConfig().getString("leave-messages." + rank.getKey());
        if (template != null && !template.isBlank()) {
            event.quitMessage(colorTemplate(template, player));
        }
        lastTrailAt.remove(player.getUniqueId());
        rankManager.unloadPlayer(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Rank rank = rankManager.getRank(player);
        if (!player.hasPermission(DEATH_MESSAGE_PERMISSION)) {
            return;
        }

        String template = plugin.getConfig().getString("death-messages." + rank.getKey());
        if (template != null && !template.isBlank()) {
            event.deathMessage(colorTemplate(template, player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(NETHERITE_TRAIL_PERMISSION)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            return;
        }
        if (from.distanceSquared(to) < 0.01D) {
            return;
        }

        long now = System.currentTimeMillis();
        Long lastRenderedAt = lastTrailAt.get(player.getUniqueId());
        if (lastRenderedAt != null && now - lastRenderedAt < TRAIL_COOLDOWN_MILLIS) {
            return;
        }

        lastTrailAt.put(player.getUniqueId(), now);
        spawnNetheriteTrail(player, to);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Rank rank = rankManager.getRank(player);
        String rawMessage = PLAIN.serialize(event.message());
        String formattedMessage = rank.isAtLeast(Rank.GOLD)
            ? replaceEmoji(ColorUtil.colorize(rawMessage), rank)
            : replaceEmoji(rawMessage, rank);

        String prefix = nameTagManager.resolvePrefix(player);
        String displayName = ColorUtil.colorize(player.getName());
        Component rendered = Component.empty()
                .append(LEGACY.deserialize(prefix))
                .append(LEGACY.deserialize(displayName))
            .append(Component.text(" » "))
                .append(LEGACY.deserialize(formattedMessage));

        event.renderer((source, sourceDisplayName, message, viewer) -> rendered);
    }

    private Component colorTemplate(String template, Player player) {
        return LEGACY.deserialize(ColorUtil.colorize(template.replace("%player%", player.getName())));
    }

    private void spawnNetheriteTrail(Player player, Location location) {
        if (location.getWorld() == null) {
            return;
        }

        Location base = location.clone()
                .subtract(location.getDirection().normalize().multiply(0.35D))
                .add(0.0D, 0.15D, 0.0D);
        location.getWorld().spawnParticle(Particle.DUST, base, 4, 0.12D, 0.04D, 0.12D, 0.0D, NETHERITE_TRAIL_DUST);
        location.getWorld().spawnParticle(Particle.SMOKE, base, 2, 0.08D, 0.02D, 0.08D, 0.0D);
    }

    private String replaceEmoji(String rawMessage, Rank rank) {
        if (!rank.isAtLeast(Rank.NETHERITE)) {
            return rawMessage;
        }

        return rawMessage
                .replace(":skull:", "☠")
                .replace(":heart:", "❤")
                .replace(":fire:", "🔥")
                .replace(":star:", "✦");
    }
}