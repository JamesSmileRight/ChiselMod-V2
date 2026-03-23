package com.chiselranks.staff;

import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.DiscordWebhookService;
import com.chiselranks.RankManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class StaffManager {
    private static final long MODERATION_COOLDOWN_MILLIS = 2000L;
    private static final int MAX_AUDIT_ENTRIES = 300;

    private final ChiselRanksPlugin plugin;
    private final RankManager rankManager;
    private final StaffLogger logger;
    private final DiscordWebhookService discordWebhookService;
    private final java.util.Map<UUID, StaffSnapshot> staffSnapshots = new ConcurrentHashMap<>();
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> staffChatToggled = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, UUID> frozenPlayers = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Set<UUID>> frozenByStaff = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Location> backLocations = new ConcurrentHashMap<>();
    private final java.util.Map<String, Long> actionCooldowns = new ConcurrentHashMap<>();
    private final List<ReportEntry> reports = new CopyOnWriteArrayList<>();
    private final List<AuditEntry> auditEntries = new CopyOnWriteArrayList<>();
    private final File dataFile;
    private YamlConfiguration dataConfig;
    private int nextReportId = 1;
    private int nextAuditId = 1;

    public StaffManager(ChiselRanksPlugin plugin, RankManager rankManager, StaffLogger logger) {
        this.plugin = plugin;
        this.rankManager = rankManager;
        this.logger = logger;
        this.discordWebhookService = plugin.getDiscordWebhookService();
        this.dataFile = new File(plugin.getDataFolder(), "staff-data.yml");
        loadData();
    }

    public StaffRole getRole(Player player) {
        return rankManager.getStaffRole(player);
    }

    public StaffRole getRole(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return getRole(online);
        }
        return rankManager.getCachedStaffRole(uuid);
    }

    public boolean isStaff(Player player) {
        return getRole(player) != StaffRole.NONE;
    }

    public boolean canUseStaffMode(Player player) {
        return getRole(player).isAtLeast(StaffRole.MOD);
    }

    public boolean canUseVanish(Player player) {
        return getRole(player).isAtLeast(StaffRole.MOD);
    }

    public boolean canUseTeleportCommands(Player player) {
        return isStaff(player);
    }

    public boolean isInStaffMode(Player player) {
        return staffSnapshots.containsKey(player.getUniqueId());
    }

    public boolean isVanished(Player player) {
        return vanishedPlayers.contains(player.getUniqueId());
    }

    public boolean isVanished(UUID uuid) {
        return vanishedPlayers.contains(uuid);
    }

    public boolean toggleStaffMode(Player player) {
        if (isInStaffMode(player)) {
            disableStaffMode(player);
            return false;
        }
        enableStaffMode(player);
        return true;
    }

    public void enableStaffMode(Player player) {
        if (!canUseStaffMode(player) || isInStaffMode(player)) {
            return;
        }

        staffSnapshots.put(player.getUniqueId(), StaffSnapshot.capture(player));
        player.setAllowFlight(true);
        player.setFlying(true);
        toggleVanish(player, true);
        logAudit(AuditType.STAFF_MODE, player.getName(), player.getName(), "Enabled staff mode");
    }

    public void disableStaffMode(Player player) {
        StaffSnapshot snapshot = staffSnapshots.remove(player.getUniqueId());
        if (snapshot != null) {
            snapshot.restore(player);
        } else {
            player.setFlying(false);
            player.setAllowFlight(false);
        }

        unfreezeTargetsFor(player.getUniqueId());
        if (isVanished(player)) {
            toggleVanish(player, false);
        }
        logAudit(AuditType.STAFF_MODE, player.getName(), player.getName(), "Disabled staff mode");
    }

    public boolean toggleVanish(Player player) {
        return toggleVanish(player, !isVanished(player));
    }

    public boolean toggleVanish(Player player, boolean vanished) {
        if (!canUseVanish(player)) {
            return false;
        }

        if (vanished) {
            vanishedPlayers.add(player.getUniqueId());
        } else {
            vanishedPlayers.remove(player.getUniqueId());
        }

        saveData();
        refreshVisibility(player);
        logAudit(AuditType.VANISH, player.getName(), player.getName(), vanished ? "Enabled vanish" : "Disabled vanish");
        return vanished;
    }

    public void refreshVisibility(Player changedPlayer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(changedPlayer)) {
                continue;
            }
            if (isStaff(viewer)) {
                viewer.showPlayer(plugin, changedPlayer);
            } else if (isVanished(changedPlayer)) {
                viewer.hidePlayer(plugin, changedPlayer);
            } else {
                viewer.showPlayer(plugin, changedPlayer);
            }
        }
    }

    public void syncViewer(Player viewer) {
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) {
                continue;
            }
            if (isStaff(viewer) || !isVanished(target)) {
                viewer.showPlayer(plugin, target);
            } else {
                viewer.hidePlayer(plugin, target);
            }
        }
    }

    public boolean canModerate(Player actor, Player target) {
        if (actor == null || target == null || actor.equals(target)) {
            return false;
        }

        StaffRole actorRole = getRole(actor);
        StaffRole targetRole = getRole(target);
        if (targetRole == StaffRole.NONE) {
            return actorRole != StaffRole.NONE;
        }
        return actorRole.isAtLeast(StaffRole.MOD) && actorRole.ordinal() > targetRole.ordinal();
    }

    public boolean isOnCooldown(Player player, String action) {
        String key = player.getUniqueId() + ":" + action;
        Long lastUse = actionCooldowns.get(key);
        if (lastUse != null && System.currentTimeMillis() - lastUse < MODERATION_COOLDOWN_MILLIS) {
            return true;
        }
        actionCooldowns.put(key, System.currentTimeMillis());
        return false;
    }

    public void rememberBackLocation(Player player, Location previousLocation) {
        if (previousLocation != null) {
            backLocations.put(player.getUniqueId(), previousLocation.clone());
        }
    }

    public Location getBackLocation(Player player) {
        Location location = backLocations.get(player.getUniqueId());
        return location == null ? null : location.clone();
    }

    public boolean isFrozen(UUID uuid) {
        return frozenPlayers.containsKey(uuid);
    }

    public void freeze(Player actor, Player target) {
        frozenPlayers.put(target.getUniqueId(), actor.getUniqueId());
        frozenByStaff.computeIfAbsent(actor.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(target.getUniqueId());
        ReportEntry caseEntry = ensureCaseForTarget(target.getName(), actor, ReportCategory.OTHER, "Staff freeze initiated.");
        caseEntry.addAction(actor.getName() + " froze " + target.getName());
        saveData();
        logAudit(AuditType.FREEZE, actor.getName(), target.getName(), "Froze player");
    }

    public void unfreeze(UUID targetUuid) {
        UUID staffUuid = frozenPlayers.remove(targetUuid);
        String actorName = resolveName(staffUuid);
        String targetName = resolveName(targetUuid);
        if (staffUuid != null) {
            Set<UUID> targets = frozenByStaff.get(staffUuid);
            if (targets != null) {
                targets.remove(targetUuid);
            }
        }
        ReportEntry caseEntry = findNewestCaseForTarget(targetName);
        if (caseEntry != null) {
            caseEntry.addAction(actorName + " unfroze " + targetName);
        }
        saveData();
        logAudit(AuditType.FREEZE, actorName, targetName, "Unfroze player");
    }

    public void unfreezeTargetsFor(UUID staffUuid) {
        Set<UUID> targets = frozenByStaff.remove(staffUuid);
        if (targets == null) {
            return;
        }
        for (UUID targetUuid : targets) {
            frozenPlayers.remove(targetUuid);
        }
        saveData();
    }

    public boolean isStaffChatToggled(Player player) {
        return staffChatToggled.contains(player.getUniqueId());
    }

    public boolean toggleStaffChat(Player player) {
        if (staffChatToggled.remove(player.getUniqueId())) {
            return false;
        }
        staffChatToggled.add(player.getUniqueId());
        return true;
    }

    public void sendStaffChat(Player sender, String message) {
        String formatted = plugin.message("messages.staff-chat-format")
                .replace("%role%", getRole(sender).getDisplayName())
                .replace("%player%", sender.getName())
                .replace("%message%", message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (isStaff(online)) {
                online.sendMessage(formatted);
            }
        }
    }

    public ReportEntry addReport(Player reporter, String targetName, String reason) {
        return addReport(reporter, targetName, reason, ReportCategory.OTHER);
    }

    public ReportEntry addReport(Player reporter, String targetName, String reason, ReportCategory category) {
        return addReport(reporter, targetName, reason, category, defaultPriorityForCategory(category));
    }

    public ReportEntry addReport(Player reporter, String targetName, String reason, ReportCategory category, ReportPriority priority) {
        ReportEntry entry = new ReportEntry(nextReportId++, reporter.getUniqueId(), reporter.getName(), targetName,
                reason, System.currentTimeMillis(), category == null ? ReportCategory.OTHER : category);
        entry.setPriority(priority == null ? defaultPriorityForCategory(category) : priority);
        entry.addAction(reporter.getName() + " created the report.");
        reports.add(entry);
        saveData();
        logAudit(AuditType.REPORT, reporter.getName(), targetName,
                "Created report #" + entry.id() + " [" + entry.category().displayName() + "] " + reason);
        return entry;
    }

    public ReportPriority defaultPriorityForCategory(ReportCategory category) {
        if (category == null) {
            return ReportPriority.LOW;
        }

        return switch (category) {
            case HACKS, KILLAURA, NBT, DUPE -> ReportPriority.URGENT;
            case XRAY, FLY, GRIEFING -> ReportPriority.HIGH;
            case ABUSE, BUG -> ReportPriority.NORMAL;
            case CHAT, OTHER -> ReportPriority.LOW;
        };
    }

    public List<ReportEntry> getReports() {
        return reports.stream()
                .sorted(Comparator
                        .comparingInt((ReportEntry entry) -> entry.status().sortOrder())
                        .thenComparingInt((ReportEntry entry) -> -entry.priority().weight())
                        .thenComparingLong(ReportEntry::updatedAt).reversed())
                .toList();
    }

    public ReportEntry getReport(int id) {
        for (ReportEntry entry : reports) {
            if (entry.id() == id) {
                return entry;
            }
        }
        return null;
    }

    public ReportEntry findNewestCaseForTarget(String targetName) {
        return reports.stream()
                .filter(entry -> entry.targetName().equalsIgnoreCase(targetName))
                .max(Comparator.comparingLong(ReportEntry::updatedAt))
                .orElse(null);
    }

    public ReportEntry findPrimaryOpenCaseForTarget(String targetName) {
        return reports.stream()
                .filter(entry -> entry.targetName().equalsIgnoreCase(targetName))
                .filter(entry -> entry.status() == ReportStatus.OPEN || entry.status() == ReportStatus.CLAIMED)
                .sorted(Comparator.comparingInt((ReportEntry entry) -> -entry.priority().weight())
                        .thenComparingLong(ReportEntry::updatedAt).reversed())
                .findFirst()
                .orElse(null);
    }

    public boolean claimReport(int id, Player staff) {
        ReportEntry entry = getReport(id);
        if (entry == null || entry.status() == ReportStatus.CLOSED || entry.status() == ReportStatus.MERGED) {
            return false;
        }
        entry.setAssignedStaff(staff.getUniqueId(), staff.getName());
        entry.setStatus(ReportStatus.CLAIMED);
        entry.addAction(staff.getName() + " claimed this case.");
        saveData();
        logAudit(AuditType.REPORT, staff.getName(), entry.targetName(), "Claimed report #" + id);
        return true;
    }

    public boolean closeReport(int id, Player staff, String resolution) {
        ReportEntry entry = getReport(id);
        if (entry == null || entry.status() == ReportStatus.MERGED) {
            return false;
        }
        entry.setAssignedStaff(staff == null ? null : staff.getUniqueId(), staff == null ? null : staff.getName());
        entry.setStatus(ReportStatus.CLOSED);
        entry.addAction((staff == null ? "System" : staff.getName()) + " closed the case"
                + (resolution == null || resolution.isBlank() ? "." : ": " + resolution));
        saveData();
        logAudit(AuditType.REPORT, staff == null ? "System" : staff.getName(), entry.targetName(), "Closed report #" + id);
        return true;
    }

    public boolean reopenReport(int id, Player staff) {
        ReportEntry entry = getReport(id);
        if (entry == null || entry.status() == ReportStatus.MERGED) {
            return false;
        }
        entry.setStatus(entry.assignedStaffUuid() == null ? ReportStatus.OPEN : ReportStatus.CLAIMED);
        entry.addAction(staff.getName() + " reopened the case.");
        saveData();
        logAudit(AuditType.REPORT, staff.getName(), entry.targetName(), "Reopened report #" + id);
        return true;
    }

    public boolean setReportPriority(int id, ReportPriority priority, Player staff) {
        ReportEntry entry = getReport(id);
        if (entry == null || priority == null) {
            return false;
        }
        entry.setPriority(priority);
        entry.addAction(staff.getName() + " set priority to " + priority.displayName() + '.');
        saveData();
        logAudit(AuditType.REPORT, staff.getName(), entry.targetName(), "Set report #" + id + " priority to " + priority.displayName());
        return true;
    }

    public boolean setReportCategory(int id, ReportCategory category, Player staff) {
        ReportEntry entry = getReport(id);
        if (entry == null || category == null) {
            return false;
        }
        entry.setCategory(category);
        entry.addAction(staff.getName() + " set category to " + category.displayName() + '.');
        saveData();
        logAudit(AuditType.REPORT, staff.getName(), entry.targetName(), "Set report #" + id + " category to " + category.displayName());
        return true;
    }

    public boolean addCaseNote(int id, Player staff, String note) {
        ReportEntry entry = getReport(id);
        if (entry == null || note == null || note.isBlank()) {
            return false;
        }
        entry.addNote(staff.getName() + ": " + note);
        entry.addAction(staff.getName() + " added a note.");
        saveData();
        logAudit(AuditType.NOTE, staff.getName(), entry.targetName(), "Added note to report #" + id);
        return true;
    }

    public boolean setEvidence(int id, Player staff, String evidence) {
        ReportEntry entry = getReport(id);
        if (entry == null) {
            return false;
        }
        entry.setEvidence(evidence == null ? "" : evidence);
        entry.addAction(staff.getName() + " updated evidence text.");
        saveData();
        logAudit(AuditType.NOTE, staff.getName(), entry.targetName(), "Updated evidence on report #" + id);
        return true;
    }

    public boolean mergeReports(int targetId, int sourceId, Player staff) {
        if (targetId == sourceId) {
            return false;
        }
        ReportEntry target = getReport(targetId);
        ReportEntry source = getReport(sourceId);
        if (target == null || source == null || source.status() == ReportStatus.MERGED) {
            return false;
        }

        source.setStatus(ReportStatus.MERGED);
        source.setMergedIntoId(targetId);
        target.addAction(staff.getName() + " merged report #" + sourceId + " into this case.");
        target.notes().addAll(source.notes());
        target.actionHistory().addAll(source.actionHistory());
        if (!source.evidence().isBlank()) {
            if (!target.evidence().isBlank()) {
                target.setEvidence(target.evidence() + "\n---\n" + source.evidence());
            } else {
                target.setEvidence(source.evidence());
            }
        }
        saveData();
        logAudit(AuditType.REPORT, staff.getName(), target.targetName(), "Merged report #" + sourceId + " into #" + targetId);
        return true;
    }

    public void removeReport(int id) {
        closeReport(id, null, "Closed from GUI");
    }

    public ReportEntry ensureCaseForTarget(String targetName, Player actor, ReportCategory category, String reason) {
        ReportEntry existing = findPrimaryOpenCaseForTarget(targetName);
        if (existing != null) {
            if (actor != null) {
                existing.setAssignedStaff(actor.getUniqueId(), actor.getName());
            }
            return existing;
        }

        ReportEntry entry = new ReportEntry(nextReportId++, actor == null ? null : actor.getUniqueId(),
                actor == null ? "System" : actor.getName(), targetName, reason, System.currentTimeMillis(),
                category == null ? ReportCategory.OTHER : category);
        if (actor != null) {
            entry.setAssignedStaff(actor.getUniqueId(), actor.getName());
            entry.setStatus(ReportStatus.CLAIMED);
        }
        entry.addAction((actor == null ? "System" : actor.getName()) + " opened this staff case.");
        reports.add(entry);
        saveData();
        return entry;
    }

    public void recordPunishment(Player actor, String targetName, String action, String reason) {
        ReportEntry entry = ensureCaseForTarget(targetName, actor, ReportCategory.OTHER, action + " - " + reason);
        entry.addAction(actor.getName() + " executed " + action + (reason == null || reason.isBlank() ? "." : ": " + reason));
        if (entry.status() == ReportStatus.OPEN) {
            entry.setStatus(ReportStatus.CLAIMED);
        }
        saveData();
        logAudit(AuditType.PUNISHMENT, actor.getName(), targetName, action + (reason == null || reason.isBlank() ? "" : " - " + reason));
    }

    public void recordInvsee(Player actor, Player target, String details) {
        logAudit(AuditType.INVSEE, actor.getName(), target.getName(), details == null ? "Opened inventory" : details);
    }

    public void recordEcsee(Player actor, Player target, String details) {
        logAudit(AuditType.ECSEE, actor.getName(), target.getName(), details == null ? "Opened ender chest" : details);
    }

    public void recordAnnouncement(String actor, String audience, String message) {
        logAudit(AuditType.ANNOUNCEMENT, actor, audience, message == null ? "Sent announcement" : message);
    }

    public void logAudit(AuditType type, String actor, String target, String details) {
        AuditEntry entry = new AuditEntry(nextAuditId++, type, actor, target, details, System.currentTimeMillis());
        auditEntries.add(0, entry);
        while (auditEntries.size() > MAX_AUDIT_ENTRIES) {
            auditEntries.remove(auditEntries.size() - 1);
        }
        logger.log('[' + type.displayName() + "] " + actor + (target == null || target.isBlank() ? "" : " -> " + target) + ": " + details);
        if (discordWebhookService != null) {
            discordWebhookService.sendStaffAudit(type.displayName(), actor + (target == null || target.isBlank() ? "" : " -> " + target) + "\n" + details);
        }
        saveData();
    }

    public List<AuditEntry> getAuditEntries() {
        return new ArrayList<>(auditEntries);
    }

    public void shutdown() {
        saveData();
    }

    private void loadData() {
        if (!dataFile.exists()) {
            dataConfig = new YamlConfiguration();
            return;
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        nextReportId = Math.max(1, dataConfig.getInt("meta.next-report-id", 1));
        nextAuditId = Math.max(1, dataConfig.getInt("meta.next-audit-id", 1));

        for (String value : dataConfig.getStringList("frozen")) {
            try {
                frozenPlayers.put(UUID.fromString(value), new UUID(0L, 0L));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String value : dataConfig.getStringList("vanished")) {
            try {
                vanishedPlayers.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
            }
        }

        ConfigurationSection reportsSection = dataConfig.getConfigurationSection("reports");
        if (reportsSection != null) {
            for (String key : reportsSection.getKeys(false)) {
                ConfigurationSection section = reportsSection.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                ReportEntry entry = ReportEntry.fromSection(section);
                if (entry != null) {
                    reports.add(entry);
                }
            }
        }

        ConfigurationSection auditSection = dataConfig.getConfigurationSection("audit");
        if (auditSection != null) {
            for (String key : auditSection.getKeys(false)) {
                ConfigurationSection section = auditSection.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                AuditEntry entry = AuditEntry.fromSection(section);
                if (entry != null) {
                    auditEntries.add(entry);
                }
            }
            auditEntries.sort(Comparator.comparingLong(AuditEntry::createdAt).reversed());
        }
    }

    private void saveData() {
        if (dataConfig == null) {
            dataConfig = new YamlConfiguration();
        }

        dataConfig.set("meta.next-report-id", nextReportId);
        dataConfig.set("meta.next-audit-id", nextAuditId);
        dataConfig.set("frozen", frozenPlayers.keySet().stream().map(UUID::toString).toList());
        dataConfig.set("vanished", vanishedPlayers.stream().map(UUID::toString).toList());
        dataConfig.set("reports", null);
        for (ReportEntry entry : reports) {
            entry.write(dataConfig.createSection("reports." + entry.id()));
        }
        dataConfig.set("audit", null);
        int auditIndex = 0;
        for (AuditEntry entry : auditEntries) {
            entry.write(dataConfig.createSection("audit." + auditIndex++));
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save staff data: " + exception.getMessage());
        }
    }

    private String resolveName(UUID uuid) {
        if (uuid == null) {
            return "System";
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            return player.getName();
        }
        return Objects.toString(Bukkit.getOfflinePlayer(uuid).getName(), uuid.toString());
    }

    public enum ReportStatus {
        OPEN(0, "Open"),
        CLAIMED(1, "Claimed"),
        CLOSED(2, "Closed"),
        MERGED(3, "Merged");

        private final int sortOrder;
        private final String displayName;

        ReportStatus(int sortOrder, String displayName) {
            this.sortOrder = sortOrder;
            this.displayName = displayName;
        }

        public int sortOrder() {
            return sortOrder;
        }

        public String displayName() {
            return displayName;
        }

        public static ReportStatus fromKey(String value) {
            if (value == null) {
                return OPEN;
            }
            for (ReportStatus status : values()) {
                if (status.name().equalsIgnoreCase(value)) {
                    return status;
                }
            }
            return OPEN;
        }
    }

    public enum ReportPriority {
        LOW(1, "Low"),
        NORMAL(2, "Normal"),
        HIGH(3, "High"),
        URGENT(4, "Urgent");

        private final int weight;
        private final String displayName;

        ReportPriority(int weight, String displayName) {
            this.weight = weight;
            this.displayName = displayName;
        }

        public int weight() {
            return weight;
        }

        public String displayName() {
            return displayName;
        }

        public static ReportPriority fromKey(String value) {
            if (value == null) {
                return NORMAL;
            }
            for (ReportPriority priority : values()) {
                if (priority.name().equalsIgnoreCase(value)) {
                    return priority;
                }
            }
            return NORMAL;
        }
    }

    public enum ReportCategory {
        HACKS("hacks", "Hacks"),
        KILLAURA("killaura", "KillAura"),
        FLY("fly", "Fly"),
        XRAY("xray", "X-Ray"),
        DUPE("dupe", "Duping"),
        NBT("nbt", "Illegal NBT"),
        CHAT("chat", "Chat"),
        ABUSE("abuse", "Abuse"),
        GRIEFING("griefing", "Griefing"),
        BUG("bug", "Bug"),
        OTHER("other", "Other");

        private final String key;
        private final String displayName;

        ReportCategory(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        public String key() {
            return key;
        }

        public String displayName() {
            return displayName;
        }

        public static ReportCategory fromKey(String value) {
            if (value == null) {
                return OTHER;
            }
            if (value.equalsIgnoreCase("cheating") || value.equalsIgnoreCase("cheat") || value.equalsIgnoreCase("hack")) {
                return HACKS;
            }
            if (value.equalsIgnoreCase("ka") || value.equalsIgnoreCase("kill-aura")) {
                return KILLAURA;
            }
            if (value.equalsIgnoreCase("spam")) {
                return CHAT;
            }
            if (value.equalsIgnoreCase("duping") || value.equalsIgnoreCase("dupes")) {
                return DUPE;
            }
            for (ReportCategory category : values()) {
                if (category.key.equalsIgnoreCase(value) || category.name().equalsIgnoreCase(value)) {
                    return category;
                }
            }
            return OTHER;
        }
    }

    public enum AuditType {
        REPORT("Report"),
        NOTE("Case Note"),
        FREEZE("Freeze"),
        INVSEE("Invsee"),
        ECSEE("Ecsee"),
        VANISH("Vanish"),
        ANNOUNCEMENT("Announcement"),
        PUNISHMENT("Punishment"),
        STAFF_MODE("Staff Mode");

        private final String displayName;

        AuditType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public static AuditType fromKey(String value) {
            if (value == null) {
                return REPORT;
            }
            for (AuditType type : values()) {
                if (type.name().equalsIgnoreCase(value)) {
                    return type;
                }
            }
            return REPORT;
        }
    }

    public static final class ReportEntry {
        private final int id;
        private final UUID reporterUuid;
        private final String reporterName;
        private final String targetName;
        private final String reason;
        private final long createdAt;
        private long updatedAt;
        private ReportCategory category;
        private ReportPriority priority;
        private ReportStatus status;
        private UUID assignedStaffUuid;
        private String assignedStaffName;
        private String evidence;
        private int mergedIntoId;
        private final List<String> notes;
        private final List<String> actionHistory;

        private ReportEntry(int id, UUID reporterUuid, String reporterName, String targetName, String reason,
                            long createdAt, ReportCategory category) {
            this.id = id;
            this.reporterUuid = reporterUuid;
            this.reporterName = reporterName == null ? "Unknown" : reporterName;
            this.targetName = targetName;
            this.reason = reason;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.category = category == null ? ReportCategory.OTHER : category;
            this.priority = ReportPriority.NORMAL;
            this.status = ReportStatus.OPEN;
            this.evidence = "";
            this.notes = new CopyOnWriteArrayList<>();
            this.actionHistory = new CopyOnWriteArrayList<>();
        }

        public int id() { return id; }
        public UUID reporterUuid() { return reporterUuid; }
        public String reporterName() { return reporterName; }
        public String targetName() { return targetName; }
        public String reason() { return reason; }
        public long createdAt() { return createdAt; }
        public long updatedAt() { return updatedAt; }
        public ReportCategory category() { return category; }
        public ReportPriority priority() { return priority; }
        public ReportStatus status() { return status; }
        public UUID assignedStaffUuid() { return assignedStaffUuid; }
        public String assignedStaffName() { return assignedStaffName; }
        public String evidence() { return evidence; }
        public int mergedIntoId() { return mergedIntoId; }
        public List<String> notes() { return notes; }
        public List<String> actionHistory() { return actionHistory; }

        public void setAssignedStaff(UUID uuid, String name) {
            this.assignedStaffUuid = uuid;
            this.assignedStaffName = name;
            this.updatedAt = System.currentTimeMillis();
        }

        public void setCategory(ReportCategory category) {
            this.category = category;
            this.updatedAt = System.currentTimeMillis();
        }

        public void setPriority(ReportPriority priority) {
            this.priority = priority;
            this.updatedAt = System.currentTimeMillis();
        }

        public void setStatus(ReportStatus status) {
            this.status = status;
            this.updatedAt = System.currentTimeMillis();
        }

        public void setEvidence(String evidence) {
            this.evidence = evidence == null ? "" : evidence;
            this.updatedAt = System.currentTimeMillis();
        }

        public void setMergedIntoId(int mergedIntoId) {
            this.mergedIntoId = mergedIntoId;
            this.updatedAt = System.currentTimeMillis();
        }

        public void addNote(String note) {
            this.notes.add(note);
            this.updatedAt = System.currentTimeMillis();
        }

        public void addAction(String action) {
            this.actionHistory.add(action);
            this.updatedAt = System.currentTimeMillis();
        }

        private void write(ConfigurationSection section) {
            section.set("id", id);
            section.set("reporter-uuid", reporterUuid == null ? null : reporterUuid.toString());
            section.set("reporter-name", reporterName);
            section.set("target-name", targetName);
            section.set("reason", reason);
            section.set("created-at", createdAt);
            section.set("updated-at", updatedAt);
            section.set("category", category.key());
            section.set("priority", priority.name());
            section.set("status", status.name());
            section.set("assigned-staff-uuid", assignedStaffUuid == null ? null : assignedStaffUuid.toString());
            section.set("assigned-staff-name", assignedStaffName);
            section.set("evidence", evidence);
            section.set("merged-into-id", mergedIntoId);
            section.set("notes", notes);
            section.set("action-history", actionHistory);
        }

        private static ReportEntry fromSection(ConfigurationSection section) {
            try {
                UUID reporterUuid = parseUuid(section.getString("reporter-uuid"));
                ReportEntry entry = new ReportEntry(
                        section.getInt("id"),
                        reporterUuid,
                        section.getString("reporter-name", "Unknown"),
                        section.getString("target-name", "Unknown"),
                        section.getString("reason", ""),
                        section.getLong("created-at", System.currentTimeMillis()),
                        ReportCategory.fromKey(section.getString("category", "other"))
                );
                entry.updatedAt = section.getLong("updated-at", entry.createdAt);
                entry.priority = ReportPriority.fromKey(section.getString("priority", "NORMAL"));
                entry.status = ReportStatus.fromKey(section.getString("status", "OPEN"));
                entry.assignedStaffUuid = parseUuid(section.getString("assigned-staff-uuid"));
                entry.assignedStaffName = section.getString("assigned-staff-name", null);
                entry.evidence = section.getString("evidence", "");
                entry.mergedIntoId = section.getInt("merged-into-id", 0);
                entry.notes.addAll(section.getStringList("notes"));
                entry.actionHistory.addAll(section.getStringList("action-history"));
                return entry;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public record AuditEntry(int id, AuditType type, String actor, String target, String details, long createdAt) {
        private void write(ConfigurationSection section) {
            section.set("id", id);
            section.set("type", type.name());
            section.set("actor", actor);
            section.set("target", target);
            section.set("details", details);
            section.set("created-at", createdAt);
        }

        private static AuditEntry fromSection(ConfigurationSection section) {
            return new AuditEntry(
                    section.getInt("id"),
                    AuditType.fromKey(section.getString("type", "REPORT")),
                    section.getString("actor", "Unknown"),
                    section.getString("target", ""),
                    section.getString("details", ""),
                    section.getLong("created-at", System.currentTimeMillis())
            );
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record StaffSnapshot(boolean allowFlight, boolean flying, GameMode gameMode) {
        private static StaffSnapshot capture(Player player) {
            return new StaffSnapshot(
                    player.getAllowFlight(),
                    player.isFlying(),
                    player.getGameMode()
            );
        }

        private void restore(Player player) {
            player.setAllowFlight(allowFlight);
            player.setFlying(allowFlight && flying);
            player.setGameMode(gameMode);
        }
    }
}