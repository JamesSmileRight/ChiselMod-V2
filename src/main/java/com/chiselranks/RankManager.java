package com.chiselranks;

import com.chiselranks.rank.Rank;
import com.chiselranks.rank.RankService;
import com.chiselranks.staff.StaffRole;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RankManager {
    private static final Set<String> GOLD_PERMISSIONS = Set.of(
        "rank.gold",
            "chiselranks.rank.gold",
            "chiselranks.perk.sit",
        "chiselranks.perk.craft",
        "chiselranks.perk.enderchest",
        "chiselranks.perk.home.1",
            "chiselranks.perk.chat.color"
    );
    private static final Set<String> DIAMOND_PERMISSIONS = Set.of(
        "rank.diamond",
            "chiselranks.rank.diamond",
        "chiselranks.perk.fly.spawn",
        "chiselranks.perk.anvil",
        "chiselranks.perk.cartographytable",
        "chiselranks.perk.stonecutter",
        "chiselranks.perk.home.3",
        "chiselranks.perk.message.join",
        "chiselranks.perk.message.leave"
    );
    private static final Set<String> NETHERITE_PERMISSIONS = Set.of(
        "rank.netherite",
            "chiselranks.rank.netherite",
        "chiselranks.perk.workbench",
        "chiselranks.perk.grindstone",
        "chiselranks.perk.loom",
        "chiselranks.perk.home.5",
        "chiselranks.perk.chat.emoji",
        "chiselranks.perk.message.death",
        "chiselranks.perk.trail.netherite"
    );

    private final ChiselRanksPlugin plugin;
    private final DatabaseManager databaseManager;
    private final RankService rankService;
    private final Map<UUID, Rank> cachedRanks = new ConcurrentHashMap<>();
    private final Map<UUID, StaffRole> cachedStaffRoles = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public RankManager(ChiselRanksPlugin plugin, DatabaseManager databaseManager, RankService rankService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.rankService = rankService;
    }

    public Rank getRank(Player player) {
        Rank resolved = cachedRanks.getOrDefault(player.getUniqueId(), rankService.getRank(player));
        Rank cached = cachedRanks.put(player.getUniqueId(), resolved);
        if (cached != resolved) {
            applyRank(player, resolved);
        }
        return resolved;
    }

    public boolean isGoldOrHigher(Player player) {
        return getRank(player).isAtLeast(Rank.GOLD);
    }

    public boolean isDiamondOrHigher(Player player) {
        return getRank(player).isAtLeast(Rank.DIAMOND);
    }

    public boolean isNetherite(Player player) {
        return getRank(player) == Rank.NETHERITE;
    }

    public String getPrefix(Rank rank) {
        if (rank == Rank.NONE) {
            return "";
        }
        return plugin.message("chat.prefix." + rank.getKey());
    }

    public void preloadRank(UUID uuid) {
        Rank loadedRank = databaseManager.loadPlayerRankSync(uuid);
        if (loadedRank != Rank.NONE || !cachedRanks.containsKey(uuid)) {
            cachedRanks.put(uuid, loadedRank);
        }

        StaffRole loadedStaffRole = databaseManager.loadPlayerStaffRoleSync(uuid);
        if (loadedStaffRole != StaffRole.NONE || !cachedStaffRoles.containsKey(uuid)) {
            cachedStaffRoles.put(uuid, loadedStaffRole);
        }
    }

    public StaffRole getStaffRole(Player player) {
        return cachedStaffRoles.getOrDefault(player.getUniqueId(), StaffRole.NONE);
    }

    public void loadPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        Rank resolved = databaseManager.loadPlayerRankSync(uuid);
        if (resolved == Rank.NONE && cachedRanks.containsKey(uuid)) {
            resolved = cachedRanks.get(uuid);
        } else {
            cachedRanks.put(uuid, resolved);
        }

        StaffRole staffRole = databaseManager.loadPlayerStaffRoleSync(uuid);
        if (staffRole == StaffRole.NONE && cachedStaffRoles.containsKey(uuid)) {
            staffRole = cachedStaffRoles.get(uuid);
        } else {
            cachedStaffRoles.put(uuid, staffRole);
        }

        applyRank(player, resolved);
    }

    public void unloadPlayer(Player player) {
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) {
            player.removeAttachment(attachment);
            player.recalculatePermissions();
        }
    }

    public Rank grantRank(UUID uuid, Rank requestedRank, GrantAction action) {
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        Rank current = cachedRanks.getOrDefault(uuid, databaseManager.loadPlayerRankSync(uuid));
        Rank next = switch (action) {
            case GRANT -> current.isAtLeast(requestedRank) ? current : requestedRank;
            case SET -> requestedRank;
            case CLEAR -> Rank.NONE;
        };

        databaseManager.savePlayerRank(uuid, next);

        if (next == Rank.NONE) {
            cachedRanks.remove(uuid);
        } else {
            cachedRanks.put(uuid, next);
        }

        if (onlinePlayer != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                applyRank(onlinePlayer, next);
                onlinePlayer.recalculatePermissions();
                onlinePlayer.sendMessage(plugin.message("messages.rank-updated")
                        .replace("%rank%", next == Rank.NONE ? "None" : next.getDisplayName()));
            });
        }
        return next;
    }

    public StaffRole setStaffRole(UUID uuid, StaffRole role) {
        StaffRole safeRole = role == null ? StaffRole.NONE : role;
        databaseManager.savePlayerStaffRole(uuid, safeRole);
        if (safeRole == StaffRole.NONE) {
            cachedStaffRoles.remove(uuid);
        } else {
            cachedStaffRoles.put(uuid, safeRole);
        }

        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                applyRank(onlinePlayer, cachedRanks.getOrDefault(uuid, Rank.NONE));
                onlinePlayer.sendMessage(plugin.message("messages.staff-role-updated")
                        .replace("%role%", safeRole == StaffRole.NONE ? "None" : safeRole.getDisplayName()));
            });
        }

        return safeRole;
    }

    public Rank getCachedRank(UUID uuid) {
        return cachedRanks.getOrDefault(uuid, Rank.NONE);
    }

    public StaffRole getCachedStaffRole(UUID uuid) {
        return cachedStaffRoles.getOrDefault(uuid, StaffRole.NONE);
    }

    public enum GrantAction {
        GRANT,
        SET,
        CLEAR
    }

    private void applyRank(Player player, Rank rank) {
        PermissionAttachment previous = attachments.remove(player.getUniqueId());
        if (previous != null) {
            player.removeAttachment(previous);
        }

        PermissionAttachment attachment = player.addAttachment(plugin);
        for (String permission : permissionsFor(rank)) {
            attachment.setPermission(permission, true);
        }
        for (String permission : staffPermissionsFor(cachedStaffRoles.getOrDefault(player.getUniqueId(), StaffRole.NONE))) {
            attachment.setPermission(permission, true);
        }

        attachments.put(player.getUniqueId(), attachment);
        player.recalculatePermissions();

        if (plugin.getTabManager() != null) {
            plugin.getTabManager().updatePlayer(player);
        }
        if (plugin.getNameTagManager() != null) {
            plugin.getNameTagManager().updatePlayer(player);
        }
    }

    private Set<String> permissionsFor(Rank rank) {
        Set<String> permissions = new LinkedHashSet<>();
        if (rank.isAtLeast(Rank.GOLD)) {
            permissions.addAll(GOLD_PERMISSIONS);
        }
        if (rank.isAtLeast(Rank.DIAMOND)) {
            permissions.addAll(DIAMOND_PERMISSIONS);
        }
        if (rank.isAtLeast(Rank.NETHERITE)) {
            permissions.addAll(NETHERITE_PERMISSIONS);
        }
        return permissions;
    }

    private Set<String> staffPermissionsFor(StaffRole role) {
        Set<String> permissions = new LinkedHashSet<>();
        if (role == null || role == StaffRole.NONE) {
            return permissions;
        }

        if (role.isAtLeast(StaffRole.HELPER)) {
            permissions.addAll(Set.of(
                    "staff.helper",
                    "staff.chat",
                    "staff.announce.staff",
                    "staff.reports",
                    "minecraft.command.tell",
                    "minecraft.command.msg",
                    "minecraft.command.reply"
            ));
        }
        if (role.isAtLeast(StaffRole.MOD)) {
            permissions.addAll(Set.of(
                    "staff.mod",
                    "staff.mode",
                    "staff.vanish",
                    "staff.freeze",
                    "staff.invsee",
                    "staff.tp",
                    "staff.rtp",
                    "staff.tphere",
                    "staff.back",
                    "staff.kick"
            ));
        }
        if (role.isAtLeast(StaffRole.SRMOD)) {
            permissions.addAll(Set.of(
                    "staff.srmod",
                    "staff.auditlog",
                    "staff.ban",
                    "staff.unban"
            ));
        }
        if (role.isAtLeast(StaffRole.ADMIN)) {
            permissions.addAll(Set.of(
                    "staff.admin",
                "staff.announce.global",
                    "staff.role.set",
                    "chisellives.admin",
                    "chiselranks.admin.villagerlock",
                    "chiselranks.admin.spawnregion",
                    "kingdomsbounty.shopnpc.admin"
            ));
        }
        if (role.isAtLeast(StaffRole.OWNER)) {
            permissions.addAll(Set.of(
                    "staff.owner",
                    "kingdomsbounty.admin.reset",
                    "leaderboard.admin",
                    "kingdomsbounty.npc.admin",
                    "chiselranks.perk.sit",
                    "chiselranks.perk.craft",
                    "chiselranks.perk.enderchest",
                    "chiselranks.perk.chat.color",
                    "chiselranks.perk.fly.spawn",
                    "chiselranks.perk.anvil",
                    "chiselranks.perk.cartographytable",
                    "chiselranks.perk.stonecutter",
                    "chiselranks.perk.message.join",
                    "chiselranks.perk.message.leave",
                    "chiselranks.perk.workbench",
                    "chiselranks.perk.grindstone",
                    "chiselranks.perk.loom",
                    "chiselranks.perk.chat.emoji",
                    "chiselranks.perk.message.death",
                    "chiselranks.perk.trail.netherite",
                    "chiselranks.admin.spawnpoint",
                    "chiselranks.admin.spawnbreak",
                    "chiselranks.admin.spawnnpc"
            ));
        }
        return permissions;
    }

    public List<Rank> getPurchasableRanks() {
        return List.of(Rank.GOLD, Rank.DIAMOND, Rank.NETHERITE);
    }
}