package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class AnnouncementCommand implements CommandExecutor {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;

    public AnnouncementCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase();
        boolean staffOnly = commandName.equals("staffannounce");
        String permission = staffOnly ? "staff.announce.staff" : "staff.announce.global";
        String usagePath = staffOnly ? "messages.staffannounce-usage" : "messages.announce-usage";

        if (!sender.hasPermission(permission)) {
            sender.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.message(usagePath));
            return true;
        }

        List<Player> recipients = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!staffOnly || staffManager.isStaff(online)) {
                recipients.add(online);
            }
        }
        if (recipients.isEmpty()) {
            sender.sendMessage(plugin.message("messages.announce-no-targets"));
            return true;
        }

        String rawMessage = String.join(" ", args);
        Component message = LEGACY.deserialize(ColorUtil.colorize(rawMessage));
        Component subtitle = staffOnly
                ? Component.text("Staff Notice", NamedTextColor.AQUA)
                : Component.text("Server Announcement", NamedTextColor.GOLD);
        Title title = Title.title(message, subtitle,
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500)));
        Sound sound = staffOnly ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.UI_TOAST_CHALLENGE_COMPLETE;
        float pitch = staffOnly ? 1.1F : 1.0F;
        Component prefix = Component.text(staffOnly ? "[Staff Notice] " : "[Announcement] ",
                staffOnly ? NamedTextColor.AQUA : NamedTextColor.GOLD);

        for (Player recipient : recipients) {
            recipient.showTitle(title);
            recipient.playSound(recipient.getLocation(), sound, 1.0F, pitch);
            recipient.sendMessage(prefix.append(message));
        }

        staffManager.recordAnnouncement(sender.getName(), staffOnly ? "staff" : "global", rawMessage);
        sender.sendMessage(plugin.message(staffOnly ? "messages.staffannounce-sent" : "messages.announce-sent"));
        return true;
    }
}