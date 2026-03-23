package com.chiselranks;

import com.beyondminer.skin.SkinHeadService;
import com.chiselranks.command.HomeCommand;
import com.chiselranks.command.LockVillagersCommand;
import com.chiselranks.command.PortableAccessCommand;
import com.chiselranks.command.SitCommand;
import com.chiselranks.command.SpawnBreakCommand;
import com.chiselranks.command.SpawnFlyCommand;
import com.chiselranks.command.SystemHelpCommand;
import com.chiselranks.command.SpawnCommand;
import com.chiselranks.home.HomeManager;
import com.chiselranks.listener.SpawnProtectionListener;
import com.chiselranks.listener.SitCleanupListener;
import com.chiselranks.listener.SpawnFlightListener;
import com.chiselranks.listener.SpawnTeleportListener;
import com.chiselranks.listener.VillagerTradeLockListener;
import com.chiselranks.manager.SitManager;
import com.chiselranks.rank.Rank;
import com.chiselranks.rank.RankService;
import com.chiselranks.staff.FreezeCommand;
import com.chiselranks.staff.AuditLogCommand;
import com.chiselranks.staff.AnnouncementCommand;
import com.chiselranks.staff.EcseeCommand;
import com.chiselranks.staff.InvseeCommand;
import com.chiselranks.staff.ModerationCommand;
import com.chiselranks.staff.NameTagManager;
import com.chiselranks.staff.ReportCommand;
import com.chiselranks.staff.ReportsCommand;
import com.chiselranks.staff.StaffChatCommand;
import com.chiselranks.staff.StaffCommand;
import com.chiselranks.staff.StaffListener;
import com.chiselranks.staff.StaffLogger;
import com.chiselranks.staff.StaffManager;
import com.chiselranks.staff.StaffRandomTeleportCommand;
import com.chiselranks.staff.StaffRoleCommand;
import com.chiselranks.staff.StaffTeleportCommand;
import com.chiselranks.staff.VanishCommand;
import com.chiselranks.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public class ChiselRanksPlugin extends JavaPlugin {
    private RankService rankService;
    private DatabaseManager databaseManager;
    private RankManager rankManager;
    private SitManager sitManager;
    private SpawnFlightListener spawnFlightListener;
    private SpawnProtectionListener spawnProtectionListener;
    private SpawnCommand spawnCommand;
    private SpawnTeleportListener spawnTeleportListener;
    private TabManager tabManager;
    private RankGUI rankGUI;
    private RankNPCManager rankNpcManager;
    private PlayerListener playerListener;
    private WebhookServer webhookServer;
    private DiscordWebhookService discordWebhookService;
    private SkinHeadService skinHeadService;
    private StaffLogger staffLogger;
    private StaffManager staffManager;
    private NameTagManager nameTagManager;
    private HomeManager homeManager;
    private ReportsCommand reportsCommand;
    private AuditLogCommand auditLogCommand;
    private PromotionBroadcaster promotionBroadcaster;
    private VillagerTradeLockListener villagerTradeLockListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        skinHeadService = new SkinHeadService(this);

        rankService = new RankService();
        sitManager = new SitManager();
        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("[ChiselRanks] Disabling plugin due to MySQL connection failure.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        rankManager = new RankManager(this, databaseManager, rankService);
        discordWebhookService = new DiscordWebhookService(this);
        staffLogger = new StaffLogger(this);
        staffManager = new StaffManager(this, rankManager, staffLogger);
        nameTagManager = new NameTagManager(this, rankManager, staffManager);
        homeManager = new HomeManager(this, databaseManager, rankManager);
        tabManager = new TabManager(nameTagManager);
        rankGUI = new RankGUI(this);
        rankNpcManager = new RankNPCManager(this, rankGUI, skinHeadService);
        villagerTradeLockListener = new VillagerTradeLockListener(this);
        spawnFlightListener = new SpawnFlightListener(this);
        spawnProtectionListener = new SpawnProtectionListener(this, spawnFlightListener);
        spawnCommand = new SpawnCommand(this);
        spawnTeleportListener = new SpawnTeleportListener(spawnCommand);
        playerListener = new PlayerListener(this, rankManager, tabManager, staffManager, nameTagManager);
        promotionBroadcaster = new PromotionBroadcaster(this);
        webhookServer = new WebhookServer(this, rankManager);
        webhookServer.start();

        registerCommands();
        registerListeners();
        refreshPromotionBroadcaster();
        rankNpcManager.loadNpc();

        for (Player player : Bukkit.getOnlinePlayers()) {
            initializeOnlinePlayer(player);
        }
        spawnFlightListener.refreshAllOnline();

        getLogger().info("ChiselRanks enabled. Cosmetic ranks are managed internally via MySQL and optional webhooks.");
    }

    @Override
    public void onDisable() {
        if (tabManager != null) {
            tabManager.stop();
        }

        if (homeManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                homeManager.unloadPlayer(player);
            }
        }

        if (sitManager != null) {
            sitManager.clearAll();
        }

        if (staffManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                staffManager.disableStaffMode(player);
            }
            staffManager.shutdown();
        }

        if (staffLogger != null) {
            staffLogger.shutdown();
        }

        if (webhookServer != null) {
            webhookServer.stop();
        }

        if (promotionBroadcaster != null) {
            promotionBroadcaster.stop();
        }

        saveConfig();

        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    public void initializeOnlinePlayer(Player player) {
        if (rankManager != null) {
            rankManager.loadPlayer(player);
        }
        if (homeManager != null) {
            homeManager.loadPlayer(player);
        }
        if (nameTagManager != null) {
            nameTagManager.updatePlayer(player);
        }
        if (tabManager != null) {
            tabManager.updatePlayer(player);
        }
    }

    public String message(String path) {
        String raw = getConfig().getString(path, "&cMissing message: " + path);
        return ColorUtil.colorize(raw);
    }

    public String rankRequirementMessage(Rank requiredRank) {
        String storeUrl = getStoreUrl();
        return message("messages.no-permission")
                .replace("%rank%", requiredRank.getDisplayName())
                .replace("%store_url%", storeUrl);
    }

    public String getStoreUrl() {
        String raw = getConfig().getString("store.url", "https://chiselsmp.online/store");
        if (raw == null || raw.isBlank()) {
            return "https://chiselsmp.online/store";
        }
        return raw.startsWith("http://") || raw.startsWith("https://") ? raw : "https://" + raw;
    }

    public RankService getRankService() {
        return rankService;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public RankManager getRankManager() {
        return rankManager;
    }

    public SitManager getSitManager() {
        return sitManager;
    }

    public TabManager getTabManager() {
        return tabManager;
    }

    public RankGUI getRankGUI() {
        return rankGUI;
    }

    public StaffManager getStaffManager() {
        return staffManager;
    }

    public NameTagManager getNameTagManager() {
        return nameTagManager;
    }

    public HomeManager getHomeManager() {
        return homeManager;
    }

    public WebhookServer getWebhookServer() {
        return webhookServer;
    }

    public DiscordWebhookService getDiscordWebhookService() {
        return discordWebhookService;
    }

    public SkinHeadService getSkinHeadService() {
        return skinHeadService;
    }

    private void registerCommands() {
        registerCommand("sit", new SitCommand(this, rankService, sitManager));
        registerCommand("spawnranksnpc", rankNpcManager);
        HomeCommand homeCommand = new HomeCommand(this, homeManager);
        registerCommand("home", homeCommand);
        registerCommand("sethome", homeCommand);
        registerCommand("delhome", homeCommand);

        registerCommand("craft", new PortableAccessCommand(this, "chiselranks.perk.craft", InventoryType.WORKBENCH, "Crafting", false));
        registerCommand("enderchest", new PortableAccessCommand(this, "chiselranks.perk.enderchest", null, "Ender Chest", true));
        registerCommand("anvil", new PortableAccessCommand(this, "chiselranks.perk.anvil", InventoryType.ANVIL, "Anvil", false));
        registerCommand("cartographytable", new PortableAccessCommand(this, "chiselranks.perk.cartographytable", InventoryType.CARTOGRAPHY, "Cartography Table", false));
        registerCommand("stonecutter", new PortableAccessCommand(this, "chiselranks.perk.stonecutter", InventoryType.STONECUTTER, "Stonecutter", false));
        registerCommand("workbench", new PortableAccessCommand(this, "chiselranks.perk.workbench", InventoryType.WORKBENCH, "Workbench", false));
        registerCommand("grindstone", new PortableAccessCommand(this, "chiselranks.perk.grindstone", InventoryType.GRINDSTONE, "Grindstone", false));
        registerCommand("loom", new PortableAccessCommand(this, "chiselranks.perk.loom", InventoryType.LOOM, "Loom", false));

        StaffCommand staffCommand = new StaffCommand(this, staffManager);
        registerCommand("staff", staffCommand, staffCommand);
        registerCommand("staffhelp", new SystemHelpCommand(this, SystemHelpCommand.Topic.STAFF));
        registerCommand("statuehelp", new SystemHelpCommand(this, SystemHelpCommand.Topic.STATUE));
        registerCommand("spawnhelp", new SystemHelpCommand(this, SystemHelpCommand.Topic.SPAWN));
        registerCommand("rankhelp", new SystemHelpCommand(this, SystemHelpCommand.Topic.RANK));
        registerCommand("vanish", new VanishCommand(this, staffManager));
        StaffChatCommand staffChatCommand = new StaffChatCommand(this, staffManager);
        registerCommand("sc", staffChatCommand);
        registerCommand("sctoggle", staffChatCommand);
        FreezeCommand freezeCommand = new FreezeCommand(this, staffManager);
        registerCommand("freeze", freezeCommand, freezeCommand);
        StaffTeleportCommand staffTeleportCommand = new StaffTeleportCommand(this, staffManager);
        registerCommand("tp", staffTeleportCommand, staffTeleportCommand);
        registerCommand("tphere", staffTeleportCommand, staffTeleportCommand);
        registerCommand("back", staffTeleportCommand, staffTeleportCommand);
        ModerationCommand moderationCommand = new ModerationCommand(this, staffManager);
        registerCommand("kick", moderationCommand, moderationCommand);
        registerCommand("ban", moderationCommand, moderationCommand);
        registerCommand("unban", moderationCommand, moderationCommand);
        InvseeCommand invseeCommand = new InvseeCommand(this, staffManager);
        registerCommand("invsee", invseeCommand, invseeCommand);
        getServer().getPluginManager().registerEvents(invseeCommand, this);
        EcseeCommand ecseeCommand = new EcseeCommand(this, staffManager);
        registerCommand("ecsee", ecseeCommand, ecseeCommand);
        ReportCommand reportCommand = new ReportCommand(this, staffManager);
        registerCommand("report", reportCommand, reportCommand);
        reportsCommand = new ReportsCommand(this, staffManager);
        registerCommand("reports", reportsCommand);
        auditLogCommand = new AuditLogCommand(this, staffManager);
        registerCommand("auditlog", auditLogCommand);
        registerCommand("staffrtp", new StaffRandomTeleportCommand(this, staffManager));
        StaffRoleCommand staffRoleCommand = new StaffRoleCommand(this, rankManager);
        registerCommand("staffrole", staffRoleCommand, staffRoleCommand);
        AnnouncementCommand announcementCommand = new AnnouncementCommand(this, staffManager);
        registerCommand("announce", announcementCommand);
        registerCommand("staffannounce", announcementCommand);
        LockVillagersCommand lockVillagersCommand = new LockVillagersCommand(this);
        registerCommand("lockvillagers", lockVillagersCommand, lockVillagersCommand);

        SpawnFlyCommand spawnFlyCommand = new SpawnFlyCommand(this, spawnFlightListener);
        SpawnBreakCommand spawnBreakCommand = new SpawnBreakCommand(this, spawnProtectionListener);
        registerCommand("spawnfly", spawnFlyCommand, spawnFlyCommand);
        registerCommand("spawnbreak", spawnBreakCommand, spawnBreakCommand);
        registerCommand("spawn", spawnCommand, spawnCommand);
        registerCommand("setspawn", spawnCommand, spawnCommand);
    }

    private void registerListeners() {
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(playerListener, this);
        pluginManager.registerEvents(rankGUI, this);
        pluginManager.registerEvents(rankNpcManager, this);
        pluginManager.registerEvents(spawnFlightListener, this);
        pluginManager.registerEvents(spawnProtectionListener, this);
        pluginManager.registerEvents(spawnTeleportListener, this);
        pluginManager.registerEvents(new StaffListener(this, staffManager, nameTagManager), this);
        if (villagerTradeLockListener != null) {
            pluginManager.registerEvents(villagerTradeLockListener, this);
        }
        if (reportsCommand != null) {
            pluginManager.registerEvents(reportsCommand, this);
        }
        if (auditLogCommand != null) {
            pluginManager.registerEvents(auditLogCommand, this);
        }
        pluginManager.registerEvents(new SitCleanupListener(this, sitManager), this);
    }

    private void registerCommand(String name, CommandExecutor executor) {
        registerCommand(name, executor, null);
    }

    private void registerCommand(String name, CommandExecutor executor, TabCompleter tabCompleter) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command /" + name + " is missing from plugin.yml");
            return;
        }

        command.setExecutor(executor);
        if (tabCompleter != null) {
            command.setTabCompleter(tabCompleter);
        }
    }

    public void refreshPromotionBroadcaster() {
        if (promotionBroadcaster == null) {
            promotionBroadcaster = new PromotionBroadcaster(this);
        }
        promotionBroadcaster.restart();
    }
}