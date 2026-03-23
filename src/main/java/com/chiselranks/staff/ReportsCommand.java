package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReportsCommand implements CommandExecutor, Listener {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ChiselRanksPlugin plugin;
    private final StaffManager staffManager;

    public ReportsCommand(ChiselRanksPlugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }
        if (!player.hasPermission("staff.reports")) {
            player.sendMessage(plugin.message("messages.staff-no-access"));
            return true;
        }

        if (args.length > 0) {
            return handleSubcommand(player, args);
        }

        openReportsMenu(player);
        return true;
    }

    private boolean handleSubcommand(Player player, String[] args) {
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "claim" -> {
                Integer reportId = parseId(player, args, 1);
                if (reportId == null) {
                    return true;
                }
                if (!staffManager.claimReport(reportId, player)) {
                    player.sendMessage("§cCould not claim that case.");
                    return true;
                }
                player.sendMessage("§aClaimed report #§f" + reportId + "§a.");
                return true;
            }
            case "close" -> {
                Integer reportId = parseId(player, args, 1);
                if (reportId == null) {
                    return true;
                }
                String resolution = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "Closed by staff";
                if (!staffManager.closeReport(reportId, player, resolution)) {
                    player.sendMessage("§cCould not close that case.");
                    return true;
                }
                player.sendMessage(plugin.message("messages.report-closed").replace("%id%", Integer.toString(reportId)));
                return true;
            }
            case "reopen" -> {
                Integer reportId = parseId(player, args, 1);
                if (reportId == null) {
                    return true;
                }
                if (!staffManager.reopenReport(reportId, player)) {
                    player.sendMessage("§cCould not reopen that case.");
                    return true;
                }
                player.sendMessage("§aReopened report #§f" + reportId + "§a.");
                return true;
            }
            case "priority" -> {
                Integer reportId = parseId(player, args, 1);
                if (reportId == null || args.length < 3) {
                    player.sendMessage("§eUsage: /reports priority <id> <low|normal|high|urgent>");
                    return true;
                }
                StaffManager.ReportPriority priority = StaffManager.ReportPriority.fromKey(args[2]);
                if (!staffManager.setReportPriority(reportId, priority, player)) {
                    player.sendMessage("§cCould not update priority.");
                    return true;
                }
                player.sendMessage("§aUpdated report #§f" + reportId + "§a priority to §f" + priority.displayName() + "§a.");
                return true;
            }
            case "category" -> {
                Integer reportId = parseId(player, args, 1);
                if (reportId == null || args.length < 3) {
                    player.sendMessage("§eUsage: /reports category <id> <hacks|killaura|fly|xray|dupe|nbt|chat|abuse|griefing|bug|other>");
                    return true;
                }
                StaffManager.ReportCategory category = StaffManager.ReportCategory.fromKey(args[2]);
                if (!staffManager.setReportCategory(reportId, category, player)) {
                    player.sendMessage("§cCould not update category.");
                    return true;
                }
                player.sendMessage("§aUpdated report #§f" + reportId + "§a category to §f" + category.displayName() + "§a.");
                return true;
            }
            case "note" -> {
                Integer reportId = parseId(player, args, 1);
                if (reportId == null || args.length < 3) {
                    player.sendMessage("§eUsage: /reports note <id> <text>");
                    return true;
                }
                String note = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                if (!staffManager.addCaseNote(reportId, player, note)) {
                    player.sendMessage("§cCould not add a note.");
                    return true;
                }
                player.sendMessage("§aAdded a note to report #§f" + reportId + "§a.");
                return true;
            }
            case "evidence" -> {
                Integer reportId = parseId(player, args, 1);
                if (reportId == null || args.length < 3) {
                    player.sendMessage("§eUsage: /reports evidence <id> <text>");
                    return true;
                }
                String evidence = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                if (!staffManager.setEvidence(reportId, player, evidence)) {
                    player.sendMessage("§cCould not update evidence.");
                    return true;
                }
                player.sendMessage("§aUpdated evidence for report #§f" + reportId + "§a.");
                return true;
            }
            case "merge" -> {
                Integer targetId = parseId(player, args, 1);
                Integer sourceId = parseId(player, args, 2);
                if (targetId == null || sourceId == null) {
                    player.sendMessage("§eUsage: /reports merge <targetId> <sourceId>");
                    return true;
                }
                if (!staffManager.mergeReports(targetId, sourceId, player)) {
                    player.sendMessage("§cCould not merge those reports.");
                    return true;
                }
                player.sendMessage("§aMerged report #§f" + sourceId + " §ainto #§f" + targetId + "§a.");
                return true;
            }
            case "tp" -> {
                Integer reportId = parseId(player, args, 1);
                if (reportId == null) {
                    return true;
                }
                StaffManager.ReportEntry entry = staffManager.getReport(reportId);
                if (entry == null) {
                    player.sendMessage("§cThat case no longer exists.");
                    return true;
                }
                teleportToTarget(player, entry);
                return true;
            }
            default -> {
                player.sendMessage("§eUsage: /reports [claim|close|reopen|priority|category|note|evidence|merge|tp]");
                return true;
            }
        }
    }

    private void openReportsMenu(Player player) {
        ReportsHolder holder = new ReportsHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Staff Cases", NamedTextColor.RED));
        holder.inventory = inventory;

        int slot = 0;
        for (StaffManager.ReportEntry entry : staffManager.getReports()) {
            if (slot >= inventory.getSize()) {
                break;
            }
            holder.slotToReportId.put(slot, entry.id());
            ItemStack itemStack = new ItemStack(materialFor(entry));
            ItemMeta meta = itemStack.getItemMeta();
            List<Component> lore = new ArrayList<>();
            meta.displayName(Component.text("Case #" + entry.id() + " - " + entry.targetName(), NamedTextColor.WHITE));
            lore.add(Component.text("Status: " + entry.status().displayName(), NamedTextColor.GRAY));
            lore.add(Component.text("Severity: " + entry.priority().displayName(), NamedTextColor.GRAY));
            lore.add(Component.text("Category: " + entry.category().displayName(), NamedTextColor.GRAY));
            lore.add(Component.text("Reporter: " + entry.reporterName(), NamedTextColor.GRAY));
            lore.add(Component.text("Assigned: " + (entry.assignedStaffName() == null ? "Unassigned" : entry.assignedStaffName()), NamedTextColor.GRAY));
            lore.add(Component.text("Updated: " + TIME_FORMAT.format(Instant.ofEpochMilli(entry.updatedAt())), NamedTextColor.DARK_GRAY));
            lore.add(Component.text(" ", NamedTextColor.BLACK));
            lore.add(Component.text("Reason: " + entry.reason(), NamedTextColor.YELLOW));
            if (!entry.evidence().isBlank()) {
                lore.add(Component.text("Evidence: " + trim(entry.evidence(), 80), NamedTextColor.AQUA));
            }
            if (!entry.notes().isEmpty()) {
                lore.add(Component.text("Latest note: " + trim(entry.notes().get(entry.notes().size() - 1), 80), NamedTextColor.LIGHT_PURPLE));
            }
            lore.add(Component.text(" ", NamedTextColor.BLACK));
            lore.add(Component.text("Left click: claim + teleport", NamedTextColor.GREEN));
            lore.add(Component.text("Right click: close case", NamedTextColor.RED));
            lore.add(Component.text("Middle click: reopen case", NamedTextColor.AQUA));
            meta.lore(lore);
            itemStack.setItemMeta(meta);
            inventory.setItem(slot++, itemStack);
        }

        if (slot == 0) {
            ItemStack itemStack = new ItemStack(Material.BARRIER);
            ItemMeta meta = itemStack.getItemMeta();
            meta.displayName(Component.text("No open staff cases", NamedTextColor.GRAY));
            itemStack.setItemMeta(meta);
            inventory.setItem(22, itemStack);
        }

        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ReportsHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Integer reportId = holder.slotToReportId.get(event.getRawSlot());
        if (reportId == null) {
            return;
        }

        StaffManager.ReportEntry entry = staffManager.getReport(reportId);
        if (entry == null) {
            return;
        }

        if (event.getClick() == ClickType.LEFT) {
            staffManager.claimReport(reportId, player);
            teleportToTarget(player, entry);
            openReportsMenu(player);
            return;
        }

        if (event.getClick() == ClickType.RIGHT) {
            staffManager.closeReport(reportId, player, "Closed from cases GUI");
            player.sendMessage(plugin.message("messages.report-closed").replace("%id%", Integer.toString(reportId)));
            openReportsMenu(player);
            return;
        }

        if (event.getClick() == ClickType.MIDDLE) {
            staffManager.reopenReport(reportId, player);
            player.sendMessage("§aReopened report #§f" + reportId + "§a.");
            openReportsMenu(player);
        }
    }

    private void teleportToTarget(Player player, StaffManager.ReportEntry entry) {
        Player target = Bukkit.getPlayerExact(entry.targetName());
        if (target == null) {
            player.sendMessage(plugin.message("messages.staff-target-not-found").replace("%player%", entry.targetName()));
            return;
        }

        player.teleport(target.getLocation());
        player.sendMessage(plugin.message("messages.staff-tp-success").replace("%player%", target.getName()));
    }

    private Integer parseId(Player player, String[] args, int index) {
        if (args.length <= index) {
            player.sendMessage("§cMissing report id.");
            return null;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException exception) {
            player.sendMessage("§cInvalid report id.");
            return null;
        }
    }

    private Material materialFor(StaffManager.ReportEntry entry) {
        return switch (entry.status()) {
            case OPEN -> switch (entry.priority()) {
                case URGENT -> Material.RED_BANNER;
                case HIGH -> Material.BLAZE_POWDER;
                case NORMAL -> Material.PAPER;
                case LOW -> Material.MAP;
            };
            case CLAIMED -> Material.WRITABLE_BOOK;
            case CLOSED -> Material.LIME_DYE;
            case MERGED -> Material.GRAY_DYE;
        };
    }

    private String trim(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static final class ReportsHolder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, Integer> slotToReportId = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}