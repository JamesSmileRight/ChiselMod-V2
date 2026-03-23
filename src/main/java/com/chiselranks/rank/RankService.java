package com.chiselranks.rank;

import org.bukkit.entity.Player;

public final class RankService {
    private static final String MODERN_GOLD = "rank.gold";
    private static final String MODERN_DIAMOND = "rank.diamond";
    private static final String MODERN_NETHERITE = "rank.netherite";
    private static final String INTERNAL_GOLD = "chiselranks.rank.gold";
    private static final String INTERNAL_DIAMOND = "chiselranks.rank.diamond";
    private static final String INTERNAL_NETHERITE = "chiselranks.rank.netherite";
    private static final String LEGACY_GOLD = "group.gold";
    private static final String LEGACY_DIAMOND = "group.diamond";
    private static final String LEGACY_NETHERITE = "group.netherite";

    public Rank getRank(Player player) {
        if (player.hasPermission(MODERN_NETHERITE) || player.hasPermission(INTERNAL_NETHERITE) || player.hasPermission(LEGACY_NETHERITE)) {
            return Rank.NETHERITE;
        }
        if (player.hasPermission(MODERN_DIAMOND) || player.hasPermission(INTERNAL_DIAMOND) || player.hasPermission(LEGACY_DIAMOND)) {
            return Rank.DIAMOND;
        }
        if (player.hasPermission(MODERN_GOLD) || player.hasPermission(INTERNAL_GOLD) || player.hasPermission(LEGACY_GOLD)) {
            return Rank.GOLD;
        }
        return Rank.NONE;
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
}
