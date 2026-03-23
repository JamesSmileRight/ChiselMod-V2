package com.chiselranks;

import com.chiselranks.staff.NameTagManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

public final class TabManager {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final NameTagManager nameTagManager;

    public TabManager(NameTagManager nameTagManager) {
        this.nameTagManager = nameTagManager;
    }

    public void start() {
        // Event-driven only.
    }

    public void stop() {
        // Event-driven only.
    }

    public void refreshAll() {
        // Event-driven only.
    }

    public void updatePlayer(Player player) {
        String baseName = player.getName();
        String tabText = nameTagManager.resolvePrefix(player) + baseName;
        player.playerListName(LEGACY.deserialize(tabText));
    }
}