package com.beyondminer.npc;

import com.beyondminer.skin.SkinHeadService;
import com.chiselranks.ChiselRanksPlugin;
import com.chiselranks.RankGUI;
import com.chiselranks.ui.GuiStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PlayerStatueManager implements CommandExecutor, TabCompleter, Listener {

    private static final String ADMIN_PERMISSION = "kingdomsbounty.statue.admin";
    private static final String NPC_ID_PREFIX = "statue:";
    private static final int SHOP_SLOT_LEFT = 11;
    private static final int SHOP_SLOT_MIDDLE = 13;
    private static final int SHOP_SLOT_RIGHT = 15;
    private static final int MENU_SLOT_ACTION = 11;
    private static final int MENU_SLOT_PREVIEW = 13;
    private static final int MENU_SLOT_INFO = 15;

    private final JavaPlugin plugin;
    private final RankGUI rankGUI;
    private final FakeNpcEngine npcEngine;
    private final StatueSkinService skinService;
    private final File storageFile;
    private final Map<String, StatueDefinition> statuesById = new ConcurrentHashMap<>();
    private final BukkitTask animationTask;
    private int animationFrame;

    public PlayerStatueManager(JavaPlugin plugin, RankGUI rankGUI, SkinHeadService skinHeadService) {
        this.plugin = plugin;
        this.rankGUI = rankGUI;
        this.skinService = new StatueSkinService(plugin, skinHeadService);
        this.npcEngine = new FakeNpcEngine(plugin, skinHeadService);
        this.storageFile = new File(plugin.getDataFolder(), "statues.yml");
        plugin.getServer().getPluginManager().registerEvents(this.npcEngine, plugin);
        this.animationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAnimatedPoses, 40L, 40L);
    }

    public void loadStatuesFromConfig() {
        npcEngine.despawnAllWithPrefix(NPC_ID_PREFIX);
        statuesById.clear();

        YamlConfiguration storage = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection root = storage.getConfigurationSection("statues");
        if (root == null || root.getKeys(false).isEmpty()) {
            return;
        }

        boolean skippedDuplicates = false;

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }

            StatueDefinition definition = StatueDefinition.fromSection(id, section);
            if (definition == null) {
                continue;
            }

            String existingId = findExistingSingletonId(definition.category());
            if (existingId != null) {
                plugin.getLogger().warning("[Statues] Skipping duplicate '" + definition.category().key()
                        + "' statue '" + definition.id() + "' because '" + existingId + "' is already loaded.");
                skippedDuplicates = true;
                continue;
            }

            statuesById.put(definition.id(), definition);
            spawnOrUpdate(definition);
        }

        if (skippedDuplicates) {
            saveAllStatues();
        }
    }

    public void clearPersistedStatues() {
        statuesById.clear();
        npcEngine.despawnAllWithPrefix(NPC_ID_PREFIX);

        YamlConfiguration storage = new YamlConfiguration();
        storage.set("statues", null);
        saveStorage(storage);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage("§cYou must be an operator to use statue commands.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "create" -> handleCreate(sender, args);
            case "remove", "delete" -> handleRemove(sender, args);
            case "reset", "clearall" -> handleReset(sender);
            case "list" -> handleList(sender);
            case "movehere", "move" -> handleMoveHere(sender, args);
            case "pose" -> handlePose(sender, args);
            case "animate" -> handleAnimate(sender, args);
            case "glow" -> handleGlow(sender, args);
            case "invisible" -> handleInvisible(sender, args);
            case "base", "baseplate" -> handleBasePlate(sender, args);
            case "hands", "arms" -> handleHands(sender, args);
            case "rename", "name", "displayname" -> handleRename(sender, args);
            case "subtitle" -> handleSubtitle(sender, args);
            case "skin" -> handleSkin(sender, args);
            case "info" -> handleInfo(sender, args);
            case "help" -> sendHelp(sender);
            default -> sendHelp(sender);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterByPrefix(List.of("create", "remove", "reset", "list", "movehere", "pose", "animate", "glow", "invisible", "base", "hands", "rename", "displayname", "subtitle", "skin", "info", "help"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return filterByPrefix(List.of("ranks", "lives", "totem", "wiki", "deco"), args[1]);
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("rename") || args[0].equalsIgnoreCase("name") || args[0].equalsIgnoreCase("displayname"))) {
            return filterByPrefix(new ArrayList<>(statuesById.keySet()), args[1]);
        }

        if (args.length == 2) {
            return filterByPrefix(new ArrayList<>(statuesById.keySet()), args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("pose")) {
            return filterByPrefix(List.of("default", "crouch", "look_left", "look_right", "bow", "thinker", "salute"), args[2]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("animate")) {
            return filterByPrefix(List.of("off", "idle", "merchant", "sentinel", "seer"), args[2]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("glow")) {
            return filterByPrefix(List.of("on", "off"), args[2]);
        }

        if (args.length == 3 && (args[0].equalsIgnoreCase("invisible") || args[0].equalsIgnoreCase("base") || args[0].equalsIgnoreCase("baseplate") || args[0].equalsIgnoreCase("hands") || args[0].equalsIgnoreCase("arms"))) {
            return filterByPrefix(List.of("on", "off"), args[2]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("skin")) {
            return filterByPrefix(List.of("premium", "offline"), args[2]);
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("create")) {
            return filterByPrefix(suggestKnownPlayerNames(), args[3]);
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("skin")) {
            return filterByPrefix(suggestKnownPlayerNames(), args[3]);
        }

        return Collections.emptyList();
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!npcEngine.isNpcEntity(event.getRightClicked())) {
            return;
        }
        handleStatueInteraction(event.getPlayer(), event.getRightClicked());
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (!npcEngine.isNpcEntity(event.getRightClicked())) {
            return;
        }
        handleStatueInteraction(event.getPlayer(), event.getRightClicked());
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        refreshMatchingStatues(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (npcEngine.isNpcEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getInventory().getHolder() instanceof PreviewHolder) {
            event.setCancelled(true);
            return;
        }

        if (event.getInventory().getHolder() instanceof StatueMenuHolder holder) {
            event.setCancelled(true);
            GuiStyle.playClick(player);
            handleMenuClick(player, holder, event.getRawSlot());
            return;
        }

        if (!(event.getInventory().getHolder() instanceof ShopHolder holder)) {
            return;
        }

        event.setCancelled(true);
        player.closeInventory();

        switch (holder.category()) {
            case LIVES -> handleLivesShopClick(player, event.getRawSlot());
            case TOTEM -> handleTotemShopClick(player, event.getRawSlot());
            default -> {
            }
        }
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can create statues.");
            return;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /statue create <ranks|lives|totem|wiki|deco> <id> [skin]");
            return;
        }

        StatueCategory category = StatueCategory.fromKey(args[1]);
        if (category == null || category == StatueCategory.QUEST) {
            sender.sendMessage("§cUnknown statue type. Use: ranks, lives, totem, wiki, or deco.");
            return;
        }

        String id = normalizeId(args[2]);
        if (!isValidId(id)) {
            sender.sendMessage("§cID must use only letters, numbers, '-' or '_' and be at most 32 characters.");
            return;
        }

        if (statuesById.containsKey(id)) {
            sender.sendMessage("§cA statue with that id already exists.");
            return;
        }

        String existingId = findExistingSingletonId(category);
        if (existingId != null) {
            sender.sendMessage("§cA " + category.key() + " statue already exists as '" + existingId + "'. Remove it first.");
            return;
        }

        String skinLookup = args.length >= 4 ? args[3] : category.defaultSkin();
        Location location = placeInFrontOf(player);

        StatueDefinition definition = category.createDefinition(id, location, skinLookup, getWikiUrl(), getStoreUrl());
        statuesById.put(id, definition);
        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fCreated §e" + id + " §fas a §b" + category.key() + " §fstatue.");
    }

    private void handleRemove(CommandSender sender, String[] args) {
        String id = resolveTargetId(sender, args, 1);
        if (id == null) {
            sender.sendMessage("§cUsage: /statue remove <id> or look at a statue.");
            return;
        }

        StatueDefinition removed = statuesById.remove(id);
        if (removed == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        npcEngine.despawnNpc(toNpcId(id));
        saveAllStatues();
        sender.sendMessage("§6[Statue] §fRemoved §e" + id + "§f.");
    }

    private void handleReset(CommandSender sender) {
        clearPersistedStatues();
        sender.sendMessage("§6[Statue] §fAll persisted statues were removed.");
    }

    private void handleList(CommandSender sender) {
        if (statuesById.isEmpty()) {
            sender.sendMessage("§6[Statue] §fNo statues have been created yet.");
            return;
        }

        sender.sendMessage("§6§l========== STATUES ==========");
        statuesById.values().stream()
                .sorted((left, right) -> left.id().compareToIgnoreCase(right.id()))
                .forEach(definition -> sender.sendMessage("§f- §e" + definition.id() + " §7(" + definition.category().key() + ", pose=" + definition.poseKey() + ")"));
        sender.sendMessage("§6§l=============================");
    }

    private void handleMoveHere(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can move statues.");
            return;
        }

        String id = resolveTargetId(sender, args, 1);
        if (id == null) {
            sender.sendMessage("§cUsage: /statue movehere <id> or look at a statue.");
            return;
        }

        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        Location location = placeInFrontOf(player);
        definition.setLocation(location);
        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fMoved §e" + id + " §fto your location.");
    }

    private void handlePose(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /statue pose <id> <default|crouch|look_left|look_right|bow|thinker|salute>");
            return;
        }

        String id = normalizeId(args[1]);
        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        FakeNpcEngine.NpcPose pose = parsePose(args[2]);
        if (pose == null) {
            sender.sendMessage("§cUnknown pose.");
            return;
        }

        definition.setPoseKey(args[2].toLowerCase(Locale.ROOT));
        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fUpdated pose for §e" + id + " §fto §b" + definition.poseKey() + "§f.");
    }

    private void handleAnimate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /statue animate <id> <off|idle|merchant|sentinel|seer>");
            return;
        }

        String id = normalizeId(args[1]);
        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        String animationKey = args[2].toLowerCase(Locale.ROOT);
        if (!List.of("off", "idle", "merchant", "sentinel", "seer").contains(animationKey)) {
            sender.sendMessage("§cUnknown animation. Use off, idle, merchant, sentinel, or seer.");
            return;
        }

        definition.setAnimationKey(animationKey);
        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fAnimation for §e" + id + " §fis now §b" + animationKey + "§f.");
    }

    private void handleGlow(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /statue glow <id> <on|off>");
            return;
        }

        String id = normalizeId(args[1]);
        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        boolean glowing = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
        definition.setGlow(glowing);
        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fGlow for §e" + id + " §fis now " + (glowing ? "§aON" : "§cOFF") + "§f.");
    }

    private void handleInvisible(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /statue invisible <id> <on|off>");
            return;
        }

        String id = normalizeId(args[1]);
        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        boolean invisible = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
        definition.setInvisible(invisible);
        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fInvisible body for §e" + id + " §fis now " + (invisible ? "§aON" : "§cOFF") + "§f.");
    }

    private void handleBasePlate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /statue base <id> <on|off>");
            return;
        }

        String id = normalizeId(args[1]);
        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        boolean basePlate = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
        definition.setBasePlate(basePlate);
        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fBase plate for §e" + id + " §fis now " + (basePlate ? "§aON" : "§cOFF") + "§f.");
    }

    private void handleHands(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /statue hands <id> <on|off>");
            return;
        }

        String id = normalizeId(args[1]);
        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        boolean hands = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
        definition.setHands(hands);
        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fHands for §e" + id + " §fis now " + (hands ? "§aON" : "§cOFF") + "§f.");
    }

    private void handleRename(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /statue rename <id> <display name>");
            return;
        }

        String id = normalizeId(args[1]);
        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        definition.setDisplayName(joinArgs(args, 2));
        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fUpdated display name for §e" + id + "§f.");
    }

    private void handleSubtitle(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /statue subtitle <id> <text|clear>");
            return;
        }

        String id = normalizeId(args[1]);
        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        String value = joinArgs(args, 2);
        definition.setSubtitle(value.equalsIgnoreCase("clear") ? "" : value);
        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fUpdated subtitle for §e" + id + "§f.");
    }

    private void handleSkin(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /statue skin <id> <premium|offline> <value>");
            return;
        }

        String id = normalizeId(args[1]);
        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        String mode = args[2].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "premium" -> {
                definition.setSkinMode(SkinMode.PREMIUM);
                definition.setSkinLookup(args[3]);
                definition.setTextureValue("");
                definition.setTextureSignature("");
            }
            case "offline" -> {
                definition.setSkinMode(SkinMode.OFFLINE);
                definition.setSkinLookup(args[3]);
                definition.setTextureValue("");
                definition.setTextureSignature("");
            }
            default -> {
                sender.sendMessage("§cUnknown skin mode. Use premium or offline.");
                return;
            }
        }

        saveAllStatues();
        spawnOrUpdate(definition);
        sender.sendMessage("§6[Statue] §fUpdated skin source for §e" + id + "§f.");
    }

    private void handleInfo(CommandSender sender, String[] args) {
        String id = resolveTargetId(sender, args, 1);
        if (id == null) {
            sender.sendMessage("§cUsage: /statue info <id> or look at a statue.");
            return;
        }

        StatueDefinition definition = statuesById.get(id);
        if (definition == null) {
            sender.sendMessage("§cNo statue found with id '" + id + "'.");
            return;
        }

        sender.sendMessage("§6§l========== STATUE INFO ==========");
        sender.sendMessage("§fId: §e" + definition.id());
        sender.sendMessage("§fType: §b" + definition.category().key());
        sender.sendMessage("§fDisplay: §a" + definition.displayName());
        sender.sendMessage("§fSubtitle: §7" + (definition.subtitle().isBlank() ? "<none>" : definition.subtitle()));
        sender.sendMessage("§fSkin: §d" + definition.skinMode().name().toLowerCase(Locale.ROOT) + " -> " + definition.skinLookup());
        sender.sendMessage("§fPose: §b" + definition.poseKey());
        sender.sendMessage("§fGlow: " + (definition.glow() ? "§aon" : "§coff"));
        sender.sendMessage("§fInvisible: " + (definition.invisible() ? "§aon" : "§coff"));
        sender.sendMessage("§fBase Plate: " + (definition.basePlate() ? "§aon" : "§coff"));
        sender.sendMessage("§fHands: " + (definition.hands() ? "§aon" : "§coff"));
        sender.sendMessage("§fWorld: §d" + definition.worldName());
        sender.sendMessage("§fXYZ: §d" + String.format(Locale.ROOT, "%.2f %.2f %.2f", definition.x(), definition.y(), definition.z()));
        sender.sendMessage("§6§l===============================");
    }

    private void handleStatueInteraction(Player player, Entity entity) {
        String npcId = npcEngine.getNpcId(entity);
        if (npcId == null || !npcId.startsWith(NPC_ID_PREFIX)) {
            return;
        }

        StatueDefinition definition = statuesById.get(npcId.substring(NPC_ID_PREFIX.length()));
        if (definition == null) {
            return;
        }

        openStatueMenu(player, definition);
    }

    private void openStatueMenu(Player player, StatueDefinition definition) {
        StatueMenuHolder holder = new StatueMenuHolder(definition.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, GuiStyle.title("Statue Menu"));
        holder.inventory = inventory;

        inventory.setItem(MENU_SLOT_ACTION, actionItem(definition));
        inventory.setItem(MENU_SLOT_PREVIEW, GuiStyle.button(Material.ENDER_EYE, previewTitle(definition), NamedTextColor.AQUA,
                List.of("Open a preview panel for this statue.")));
        inventory.setItem(MENU_SLOT_INFO, GuiStyle.button(Material.BOOK, "Info", NamedTextColor.GOLD,
                List.of("View details about this statue and its role.")));
        GuiStyle.frame(inventory);
        player.openInventory(inventory);
        GuiStyle.playOpen(player);
    }

    private ItemStack actionItem(StatueDefinition definition) {
        return switch (definition.category()) {
            case RANKS, LIVES, TOTEM -> GuiStyle.button(Material.EMERALD, "Shop", NamedTextColor.GREEN,
                    List.of("Open the linked store action for this statue."));
            case WIKI -> GuiStyle.button(Material.WRITABLE_BOOK, "Open Wiki", NamedTextColor.YELLOW,
                    List.of("Open the wiki link for guides and help."));
            case QUEST, DECO -> GuiStyle.button(Material.ARMOR_STAND, "Inspect", NamedTextColor.WHITE,
                    List.of("Decorative statue. Use preview or info for details."));
        };
    }

    private String previewTitle(StatueDefinition definition) {
        return switch (definition.category()) {
            case WIKI -> "Guide Preview";
            default -> "Preview";
        };
    }

    private void handleMenuClick(Player player, StatueMenuHolder holder, int rawSlot) {
        StatueDefinition definition = statuesById.get(holder.statueId());
        if (definition == null) {
            GuiStyle.playError(player);
            player.closeInventory();
            return;
        }

        switch (rawSlot) {
            case MENU_SLOT_ACTION -> runPrimaryAction(player, definition);
            case MENU_SLOT_PREVIEW -> runPreviewAction(player, definition);
            case MENU_SLOT_INFO -> showStatueInfo(player, definition);
            default -> {
                return;
            }
        }
    }

    private void runPrimaryAction(Player player, StatueDefinition definition) {
        switch (definition.category()) {
            case RANKS -> sendUrlMessage(player, "Store", getStoreUrl(), "Open the ranks shop.");
            case LIVES -> sendUrlMessage(player, "Store", getStoreUrl(), "Open the lives shop.");
            case TOTEM -> sendUrlMessage(player, "Store", getStoreUrl(), "Open the Revival Totem shop.");
            case WIKI -> sendUrlMessage(player, "Wiki", definition.actionUrl().isBlank() ? getWikiUrl() : definition.actionUrl(), "Open the Chisel wiki and guides.");
            case QUEST, DECO -> showStatueInfo(player, definition);
        }
    }

    private void runPreviewAction(Player player, StatueDefinition definition) {
        switch (definition.category()) {
            case RANKS -> rankGUI.open(player);
            case LIVES -> openLivesShop(player);
            case TOTEM -> openTotemShop(player);
            case WIKI -> openWikiPreview(player, definition);
            case QUEST, DECO -> showStatueInfo(player, definition);
        }
    }

    private void showStatueInfo(Player player, StatueDefinition definition) {
        GuiStyle.playConfirm(player);
        player.closeInventory();
        player.sendMessage("§6§l========== STATUE INFO ==========");
        player.sendMessage("§fId: §e" + definition.id());
        player.sendMessage("§fType: §b" + definition.category().key());
        player.sendMessage("§fDisplay: §a" + definition.displayName());
        player.sendMessage("§fSubtitle: §7" + (definition.subtitle().isBlank() ? "<none>" : definition.subtitle()));
        player.sendMessage("§fPose: §b" + definition.poseKey() + " §7(animation=" + definition.animationKey() + ")");
        player.sendMessage("§6§l===============================");
    }

    private void handleLivesShopClick(Player player, int rawSlot) {
        int amount = switch (rawSlot) {
            case SHOP_SLOT_LEFT -> 1;
            case SHOP_SLOT_MIDDLE -> 5;
            case SHOP_SLOT_RIGHT -> 10;
            default -> 0;
        };

        if (amount <= 0) {
            return;
        }

        GuiStyle.playConfirm(player);
        sendUrlMessage(player, "Store", getStoreUrl(), amount + " lives selected. Complete the purchase on the website.");
    }

    private void handleTotemShopClick(Player player, int rawSlot) {
        if (rawSlot != SHOP_SLOT_MIDDLE) {
            return;
        }

        GuiStyle.playConfirm(player);
        sendUrlMessage(player, "Store", getStoreUrl(), "Revival Totem selected. Complete the purchase on the website.");
    }

    private void openLivesShop(Player player) {
        ShopHolder holder = new ShopHolder(StatueCategory.LIVES);
        Inventory inventory = Bukkit.createInventory(holder, 27, GuiStyle.title("Lives Shop"));
        holder.inventory = inventory;
        inventory.setItem(SHOP_SLOT_LEFT, buildShopItem(Material.RED_DYE, "1 Life", List.of("Quick recovery after a death.", "Keep your run alive when you are low.", "Click to open the store."), NamedTextColor.RED));
        inventory.setItem(SHOP_SLOT_MIDDLE, buildShopItem(Material.GOLDEN_APPLE, "5 Lives", List.of("Best value for active players.", "Stack lives toward the 15 life cap.", "Click to open the store."), NamedTextColor.GOLD));
        inventory.setItem(SHOP_SLOT_RIGHT, buildShopItem(Material.NETHER_STAR, "10 Lives", List.of("Large bundle for grinders.", "Players can buy up to 15 lives total.", "Click to open the store."), NamedTextColor.AQUA));
        GuiStyle.frame(inventory);
        player.openInventory(inventory);
        GuiStyle.playOpen(player);
    }

    private void openTotemShop(Player player) {
        ShopHolder holder = new ShopHolder(StatueCategory.TOTEM);
        Inventory inventory = Bukkit.createInventory(holder, 27, GuiStyle.title("Revival Totem"));
        holder.inventory = inventory;
        inventory.setItem(SHOP_SLOT_MIDDLE, buildShopItem(Material.TOTEM_OF_UNDYING, "Revival Totem", List.of("Revive a banned teammate.", "Save a friend and bring them back into the world.", "Click to open the store."), NamedTextColor.GOLD));
        GuiStyle.frame(inventory);
        player.openInventory(inventory);
        GuiStyle.playOpen(player);
    }

    private void openWikiPreview(Player player, StatueDefinition definition) {
        Inventory inventory = Bukkit.createInventory(new PreviewHolder(), 27, GuiStyle.title("Wiki Preview"));
        inventory.setItem(11, GuiStyle.button(Material.BOOKSHELF, "Starter Guides", NamedTextColor.AQUA,
                List.of("Kingdoms, lives, and rank guidance.", "Use the action button to open the wiki.")));
        inventory.setItem(13, GuiStyle.button(Material.MAP, "Server Systems", NamedTextColor.GOLD,
                List.of("Bounties, wars, and progression overviews.")));
        inventory.setItem(15, GuiStyle.button(Material.WRITABLE_BOOK, "URL", NamedTextColor.YELLOW,
                List.of(definition.actionUrl().isBlank() ? getWikiUrl() : definition.actionUrl())));
        GuiStyle.frame(inventory);
        player.openInventory(inventory);
        GuiStyle.playOpen(player);
    }

    private ItemStack buildShopItem(Material material, String name, List<String> lore, NamedTextColor color) {
        return GuiStyle.button(material, name, color, lore);
    }

    private void sendUrlMessage(Player player, String source, String url, String description) {
        String normalizedUrl = normalizeUrl(url);
        Component button = Component.text("[OPEN LINK]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.openUrl(normalizedUrl))
                .hoverEvent(HoverEvent.showText(Component.text(normalizedUrl, NamedTextColor.GRAY)));

        player.sendMessage(Component.text("[" + source + "] ", NamedTextColor.GOLD)
                .append(Component.text(description, NamedTextColor.WHITE)));
        player.sendMessage(button);
    }

    private void spawnOrUpdate(StatueDefinition definition) {
        World world = Bukkit.getWorld(definition.worldName());
        if (world == null) {
            plugin.getLogger().warning("[Statues] Could not spawn statue '" + definition.id() + "': missing world " + definition.worldName());
            return;
        }

        Location location = new Location(world, definition.x(), definition.y(), definition.z(), definition.yaw(), definition.pitch());
        FakeNpcEngine.NpcPose pose = resolvePose(definition);

        resolveSkin(definition).whenComplete((resolvedSkin, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.WARNING, "[Statues] Failed to resolve skin for statue " + definition.id(), throwable);
            }

            StatueDefinition current = statuesById.get(definition.id());
            if (current != definition) {
                return;
            }

            String textureValue = resolvedSkin == null ? "" : resolvedSkin.value();
            String textureSignature = resolvedSkin == null ? "" : resolvedSkin.signature();
            String profileName = definition.skinLookup().isBlank() ? definition.id() : definition.skinLookup();

            Bukkit.getScheduler().runTask(plugin, () -> npcEngine.spawnNpc(
                    toNpcId(definition.id()),
                    new FakeNpcEngine.NpcRequest(
                            location,
                            profileName,
                            definition.displayName(),
                            definition.subtitle(),
                            textureValue,
                            textureSignature,
                            true,
                            definition.glow(),
                                pose,
                                definition.invisible(),
                                definition.basePlate(),
                                definition.hands()
                    )
            ));
        });
    }

    private CompletableFuture<StatueSkinService.ResolvedSkin> resolveSkin(StatueDefinition definition) {
        return switch (definition.skinMode()) {
            case PREMIUM -> skinService.resolvePremium(definition.skinLookup());
            case OFFLINE -> skinService.resolveOffline(definition.skinLookup());
        };
    }

    private void refreshMatchingStatues(Player player) {
        String playerName = player.getName();
        String playerUuid = player.getUniqueId().toString();
        for (StatueDefinition definition : statuesById.values()) {
            if (definition.skinMode() != SkinMode.OFFLINE) {
                continue;
            }

            String lookup = definition.skinLookup();
            if (lookup == null || lookup.isBlank()) {
                continue;
            }

            if (!lookup.equalsIgnoreCase(playerName) && !lookup.equalsIgnoreCase(playerUuid)) {
                continue;
            }

            spawnOrUpdate(definition);
        }
    }

    private void tickAnimatedPoses() {
        animationFrame++;
        for (StatueDefinition definition : statuesById.values()) {
            if ("off".equals(definition.animationKey())) {
                continue;
            }
            World world = Bukkit.getWorld(definition.worldName());
            if (world == null) {
                continue;
            }
            boolean viewerNearby = world.getPlayers().stream().anyMatch(player -> player.getLocation().distanceSquared(
                    new Location(world, definition.x(), definition.y(), definition.z())) <= (48.0D * 48.0D));
            if (!viewerNearby) {
                continue;
            }
            npcEngine.updateNpcPose(toNpcId(definition.id()), resolvePose(definition));
        }
    }

    private FakeNpcEngine.NpcPose resolvePose(StatueDefinition definition) {
        if (!"off".equals(definition.animationKey())) {
            List<FakeNpcEngine.NpcPose> frames = animationFrames(definition.animationKey());
            if (!frames.isEmpty()) {
                return frames.get(animationFrame % frames.size());
            }
        }
        return Objects.requireNonNullElse(parsePose(definition.poseKey()), FakeNpcEngine.NpcPose.DEFAULT);
    }

    private List<FakeNpcEngine.NpcPose> animationFrames(String animationKey) {
        return switch (animationKey) {
            case "idle" -> List.of(FakeNpcEngine.NpcPose.DEFAULT, FakeNpcEngine.NpcPose.LOOK_LEFT,
                    FakeNpcEngine.NpcPose.DEFAULT, FakeNpcEngine.NpcPose.LOOK_RIGHT);
            case "merchant" -> List.of(FakeNpcEngine.NpcPose.DEFAULT, FakeNpcEngine.NpcPose.THINKER,
                    FakeNpcEngine.NpcPose.LOOK_LEFT, FakeNpcEngine.NpcPose.LOOK_RIGHT);
            case "sentinel" -> List.of(FakeNpcEngine.NpcPose.DEFAULT, FakeNpcEngine.NpcPose.SALUTE,
                    FakeNpcEngine.NpcPose.LOOK_LEFT, FakeNpcEngine.NpcPose.LOOK_RIGHT);
            case "seer" -> List.of(FakeNpcEngine.NpcPose.THINKER, FakeNpcEngine.NpcPose.BOW,
                    FakeNpcEngine.NpcPose.LOOK_LEFT, FakeNpcEngine.NpcPose.THINKER);
            default -> List.of();
        };
    }

    private String stripLegacy(String value) {
        return value == null ? "" : value.replace('&', '§');
    }

    private void saveAllStatues() {
        YamlConfiguration storage = new YamlConfiguration();
        for (StatueDefinition definition : statuesById.values()) {
            definition.write(storage, "statues." + definition.id());
        }
        saveStorage(storage);
    }

    private void saveStorage(YamlConfiguration storage) {
        try {
            storage.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "[Statues] Failed to save statues.yml", exception);
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6§l========== STATUE COMMANDS ==========");
        sender.sendMessage("§f/statue create <type> <id> [skin] §8- Create a ranks, lives, totem, wiki, or deco statue");
        sender.sendMessage("§f/statue remove [id] §8- Remove a statue or the one you are looking at");
        sender.sendMessage("§f/statue reset §8- Remove all persisted statues");
        sender.sendMessage("§f/statue movehere [id] §8- Move a statue to your location");
        sender.sendMessage("§f/statue pose <id> <pose> §8- Change the full-body pose preset");
        sender.sendMessage("§f/statue animate <id> <off|idle|merchant|sentinel|seer> §8- Set a low-cost pose animation loop");
        sender.sendMessage("§f/statue glow <id> <on|off> §8- Toggle glowing");
        sender.sendMessage("§f/statue invisible <id> <on|off> §8- Toggle armor stand body visibility");
        sender.sendMessage("§f/statue base <id> <on|off> §8- Toggle the armor stand base plate");
        sender.sendMessage("§f/statue hands <id> <on|off> §8- Toggle the armor stand hands");
        sender.sendMessage("§f/statue rename <id> <name> §8- Change the display name");
        sender.sendMessage("§f/statue displayname <id> <name> §8- Alias for rename");
        sender.sendMessage("§f/statue subtitle <id> <text|clear> §8- Change the subtitle line");
        sender.sendMessage("§f/statue skin <id> <premium|offline> <value> §8- Use Mojang or SkinRestorer-backed skin data only");
        sender.sendMessage("§f/statue help §8- Show this statue command list");
        sender.sendMessage("§f/statue list §8- List all statues");
        sender.sendMessage("§f/statue info [id] §8- Show statue details");
        sender.sendMessage("§6§l=====================================");
    }

    private String resolveTargetId(CommandSender sender, String[] args, int idIndex) {
        if (args.length > idIndex) {
            return normalizeId(args[idIndex]);
        }

        if (!(sender instanceof Player player)) {
            return null;
        }

        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                6.0D,
                0.4D,
                npcEngine::isNpcEntity
        );
        if (result == null || result.getHitEntity() == null) {
            return null;
        }

        String npcId = npcEngine.getNpcId(result.getHitEntity());
        if (npcId == null || !npcId.startsWith(NPC_ID_PREFIX)) {
            return null;
        }

        return npcId.substring(NPC_ID_PREFIX.length());
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return !(sender instanceof Player player) || player.isOp() || player.hasPermission(ADMIN_PERMISSION);
    }

    private String findExistingSingletonId(StatueCategory category) {
        if (category == null || !category.isSingleton()) {
            return null;
        }

        for (StatueDefinition definition : statuesById.values()) {
            if (definition.category() == category) {
                return definition.id();
            }
        }

        return null;
    }

    private Location placeInFrontOf(Player player) {
        Location current = player.getLocation();
        Location location = new Location(
                current.getWorld(),
                current.getBlockX() + 0.5D,
                current.getY(),
                current.getBlockZ() + 0.5D,
                current.getYaw(),
                0.0F
        );
        location.setYaw(player.getLocation().getYaw());
        location.setPitch(0.0F);
        return location;
    }

    private List<String> suggestKnownPlayerNames() {
        List<String> names = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return names.isEmpty() ? List.of("Steve", "Alex") : names;
    }

    private List<String> filterByPrefix(List<String> options, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private String normalizeId(String rawId) {
        return rawId.toLowerCase(Locale.ROOT).trim();
    }

    private boolean isValidId(String id) {
        return id.matches("^[a-z0-9_-]{1,32}$");
    }

    private FakeNpcEngine.NpcPose parsePose(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "default" -> FakeNpcEngine.NpcPose.DEFAULT;
            case "crouch", "sneak" -> FakeNpcEngine.NpcPose.CROUCH;
            case "look_left", "left" -> FakeNpcEngine.NpcPose.LOOK_LEFT;
            case "look_right", "right" -> FakeNpcEngine.NpcPose.LOOK_RIGHT;
            case "bow" -> FakeNpcEngine.NpcPose.BOW;
            case "thinker" -> FakeNpcEngine.NpcPose.THINKER;
            case "salute" -> FakeNpcEngine.NpcPose.SALUTE;
            default -> null;
        };
    }

    private String joinArgs(String[] args, int startIndex) {
        StringBuilder builder = new StringBuilder();
        for (int index = startIndex; index < args.length; index++) {
            if (index > startIndex) {
                builder.append(' ');
            }
            builder.append(args[index]);
        }
        return builder.toString();
    }

    private String toNpcId(String id) {
        return NPC_ID_PREFIX + id;
    }

    private String getStoreUrl() {
        if (plugin instanceof ChiselRanksPlugin ranksPlugin) {
            return ranksPlugin.getStoreUrl();
        }
        return normalizeUrl(plugin.getConfig().getString("store.url", "https://chiselcraft.online/store"));
    }

    private String getWikiUrl() {
        return normalizeUrl(plugin.getConfig().getString("wiki.url", "https://chiselcraft.online/wiki"));
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://chiselcraft.online";
        }
        return url.startsWith("http://") || url.startsWith("https://") ? url : "https://" + url;
    }

    private enum SkinMode {
        PREMIUM,
        OFFLINE;

        private static SkinMode fromKey(String value) {
            if (value == null || value.isBlank()) {
                return PREMIUM;
            }

            return switch (value.toLowerCase(Locale.ROOT)) {
                case "offline" -> OFFLINE;
                case "local", "file", "folder", "texture", "direct" -> OFFLINE;
                default -> PREMIUM;
            };
        }
    }

    private enum StatueCategory {
        RANKS("ranks", "&6Ranks", "&7Browse rank upgrades", "Steve"),
        LIVES("lives", "&bLives", "&7Buy extra lives", "Alex"),
        TOTEM("totem", "&6Revival Totem", "&7Restore a banned teammate", "Steve"),
        WIKI("wiki", "&eWiki", "&7Open guides and help", "Alex"),
        QUEST("quest", "&dQuest Guide", "&7Talk to begin your journey", "Alex"),
        DECO("deco", "&fStatue", "", "Steve");

        private final String key;
        private final String defaultName;
        private final String defaultSubtitle;
        private final String defaultSkin;

        StatueCategory(String key, String defaultName, String defaultSubtitle, String defaultSkin) {
            this.key = key;
            this.defaultName = defaultName;
            this.defaultSubtitle = defaultSubtitle;
            this.defaultSkin = defaultSkin;
        }

        private static StatueCategory fromKey(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            for (StatueCategory category : values()) {
                if (category.key.equalsIgnoreCase(value)) {
                    return category;
                }
            }
            return null;
        }

        private StatueDefinition createDefinition(String id, Location location, String skinLookup, String wikiUrl, String storeUrl) {
            String actionUrl = switch (this) {
                case WIKI -> wikiUrl;
                case RANKS, LIVES, TOTEM -> storeUrl;
                case QUEST, DECO -> "";
            };
            return new StatueDefinition(
                    id,
                    key,
                    Objects.requireNonNull(location.getWorld()).getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch(),
                    defaultName,
                    defaultSubtitle,
                    true,
                    false,
                    false,
                    false,
                    true,
                    "default",
                    this == QUEST ? "idle" : "off",
                    SkinMode.OFFLINE,
                    skinLookup,
                    "",
                    "",
                    actionUrl,
                    this == QUEST ? "hunter" : ""
            );
        }

        private String key() {
            return key;
        }

        private String defaultSkin() {
            return defaultSkin;
        }

        private boolean isSingleton() {
            return this != DECO && this != QUEST;
        }
    }

    private static final class ShopHolder implements InventoryHolder {
        private final StatueCategory category;
        private Inventory inventory;

        private ShopHolder(StatueCategory category) {
            this.category = category;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private StatueCategory category() {
            return category;
        }
    }

    private static final class StatueMenuHolder implements InventoryHolder {
        private final String statueId;
        private Inventory inventory;

        private StatueMenuHolder(String statueId) {
            this.statueId = statueId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private String statueId() {
            return statueId;
        }
    }

    private static final class DialogueHolder implements InventoryHolder {
        private final StatueDefinition definition;
        private final QuestDialogueTree tree;
        private final String nodeKey;
        private Inventory inventory;

        private DialogueHolder(StatueDefinition definition, QuestDialogueTree tree, String nodeKey) {
            this.definition = definition;
            this.tree = tree;
            this.nodeKey = nodeKey;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private StatueDefinition definition() {
            return definition;
        }

        private QuestDialogueTree tree() {
            return tree;
        }

        private String nodeKey() {
            return nodeKey;
        }
    }

    private static final class PreviewHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class StatueDefinition {
        private final String id;
        private final StatueCategory category;
        private String worldName;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private String displayName;
        private String subtitle;
        private boolean nameVisible;
        private boolean glow;
        private boolean invisible;
        private boolean basePlate;
        private boolean hands;
        private String poseKey;
        private String animationKey;
        private SkinMode skinMode;
        private String skinLookup;
        private String textureValue;
        private String textureSignature;
        private String actionUrl;
        private String dialogueKey;

        private StatueDefinition(String id, String categoryKey, String worldName, double x, double y, double z,
                                 float yaw, float pitch, String displayName, String subtitle, boolean nameVisible,
                     boolean glow, boolean invisible, boolean basePlate, boolean hands, String poseKey, String animationKey, SkinMode skinMode, String skinLookup,
                     String textureValue, String textureSignature, String actionUrl, String dialogueKey) {
            this.id = id;
            this.category = StatueCategory.fromKey(categoryKey);
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.displayName = displayName == null ? id : displayName;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.nameVisible = nameVisible;
            this.glow = glow;
            this.invisible = invisible;
            this.basePlate = basePlate;
            this.hands = hands;
            this.poseKey = poseKey == null ? "default" : poseKey;
            this.animationKey = animationKey == null || animationKey.isBlank() ? "off" : animationKey;
            this.skinMode = skinMode == null ? SkinMode.OFFLINE : skinMode;
            this.skinLookup = skinLookup == null ? "Steve" : skinLookup;
            this.textureValue = textureValue == null ? "" : textureValue;
            this.textureSignature = textureSignature == null ? "" : textureSignature;
            this.actionUrl = actionUrl == null ? "" : actionUrl;
            this.dialogueKey = dialogueKey == null ? "" : dialogueKey;
        }

        private static StatueDefinition fromSection(String id, ConfigurationSection section) {
            StatueCategory category = StatueCategory.fromKey(section.getString("type", "deco"));
            if (category == StatueCategory.QUEST) {
                category = StatueCategory.DECO;
            }
            String world = section.getString("world");
            if (category == null || world == null || world.isBlank()) {
                return null;
            }

            return new StatueDefinition(
                    id,
                    category.key(),
                    world,
                    section.getDouble("x"),
                    section.getDouble("y"),
                    section.getDouble("z"),
                    (float) section.getDouble("yaw", 0.0D),
                    (float) section.getDouble("pitch", 0.0D),
                    section.getString("display-name", category.defaultName),
                    section.getString("subtitle", category.defaultSubtitle),
                    section.getBoolean("name-visible", true),
                    section.getBoolean("glow", false),
                    section.getBoolean("invisible", false),
                    section.getBoolean("base-plate", false),
                    section.getBoolean("hands", true),
                    section.getString("pose", "default"),
                    section.getString("animation", "off"),
                    SkinMode.fromKey(section.getString("skin.mode", "offline")),
                    section.getString("skin.lookup", category.defaultSkin),
                    section.getString("skin.texture-value", ""),
                    section.getString("skin.texture-signature", ""),
                    section.getString("action-url", ""),
                    section.getString("dialogue", category == StatueCategory.QUEST ? "hunter" : "")
            );
        }

        private void write(YamlConfiguration storage, String basePath) {
            storage.set(basePath + ".type", category.key());
            storage.set(basePath + ".world", worldName);
            storage.set(basePath + ".x", x);
            storage.set(basePath + ".y", y);
            storage.set(basePath + ".z", z);
            storage.set(basePath + ".yaw", yaw);
            storage.set(basePath + ".pitch", pitch);
            storage.set(basePath + ".display-name", displayName);
            storage.set(basePath + ".subtitle", subtitle);
            storage.set(basePath + ".name-visible", nameVisible);
            storage.set(basePath + ".glow", glow);
            storage.set(basePath + ".invisible", invisible);
            storage.set(basePath + ".base-plate", basePlate);
            storage.set(basePath + ".hands", hands);
            storage.set(basePath + ".pose", poseKey);
            storage.set(basePath + ".animation", animationKey);
            storage.set(basePath + ".skin.mode", skinMode.name().toLowerCase(Locale.ROOT));
            storage.set(basePath + ".skin.lookup", skinLookup);
            storage.set(basePath + ".skin.texture-value", textureValue);
            storage.set(basePath + ".skin.texture-signature", textureSignature);
            storage.set(basePath + ".action-url", actionUrl);
            storage.set(basePath + ".dialogue", dialogueKey);
        }

        private String id() {
            return id;
        }

        private StatueCategory category() {
            return category;
        }

        private String worldName() {
            return worldName;
        }

        private double x() {
            return x;
        }

        private double y() {
            return y;
        }

        private double z() {
            return z;
        }

        private float yaw() {
            return yaw;
        }

        private float pitch() {
            return pitch;
        }

        private String displayName() {
            return displayName;
        }

        private String subtitle() {
            return subtitle;
        }

        private boolean glow() {
            return glow;
        }

        private boolean invisible() {
            return invisible;
        }

        private boolean basePlate() {
            return basePlate;
        }

        private boolean hands() {
            return hands;
        }

        private String poseKey() {
            return poseKey;
        }

        private String animationKey() {
            return animationKey;
        }

        private SkinMode skinMode() {
            return skinMode;
        }

        private String skinLookup() {
            return skinLookup;
        }

        private String textureValue() {
            return textureValue;
        }

        private String textureSignature() {
            return textureSignature;
        }

        private String actionUrl() {
            return actionUrl;
        }

        private String dialogueKey() {
            return dialogueKey;
        }

        private void setLocation(Location location) {
            this.worldName = Objects.requireNonNull(location.getWorld()).getName();
            this.x = location.getX();
            this.y = location.getY();
            this.z = location.getZ();
            this.yaw = location.getYaw();
            this.pitch = location.getPitch();
        }

        private void setPoseKey(String poseKey) {
            this.poseKey = poseKey;
        }

        private void setAnimationKey(String animationKey) {
            this.animationKey = animationKey == null || animationKey.isBlank() ? "off" : animationKey;
        }

        private void setGlow(boolean glow) {
            this.glow = glow;
        }

        private void setInvisible(boolean invisible) {
            this.invisible = invisible;
        }

        private void setBasePlate(boolean basePlate) {
            this.basePlate = basePlate;
        }

        private void setHands(boolean hands) {
            this.hands = hands;
        }

        private void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        private void setSubtitle(String subtitle) {
            this.subtitle = subtitle == null ? "" : subtitle;
        }

        private void setSkinMode(SkinMode skinMode) {
            this.skinMode = skinMode;
        }

        private void setSkinLookup(String skinLookup) {
            this.skinLookup = skinLookup == null ? "Steve" : skinLookup;
        }

        private void setTextureValue(String textureValue) {
            this.textureValue = textureValue == null ? "" : textureValue;
        }

        private void setTextureSignature(String textureSignature) {
            this.textureSignature = textureSignature == null ? "" : textureSignature;
        }

        private void setDialogueKey(String dialogueKey) {
            this.dialogueKey = dialogueKey == null ? "" : dialogueKey;
        }
        }

        private record DialogueOption(String prompt, String response, String nextNodeKey) {
            private String label() {
                return prompt;
            }

            private boolean endConversation() {
                return nextNodeKey == null || nextNodeKey.isBlank();
            }
        }

        private record QuestDialogueNode(String key, String speakerLine, List<DialogueOption> options) {
            private String text() {
                return speakerLine;
            }
        }

        private record QuestDialogueTree(String rootNodeKey, String summary, String rewardHint, Map<String, QuestDialogueNode> nodes) {
        private static QuestDialogueTree hunter() {
            return new QuestDialogueTree(
                "intro",
                        "Learn how bounty hunters track targets, choose engagements, and extract cleanly.",
                        "Better target selection and cleaner survival instincts.",
                Map.of(
                    "intro", new QuestDialogueNode(
                        "intro",
                        "Bounties move fast. If you want coin, track players near the war frontier and strike with purpose.",
                        List.of(
                            new DialogueOption("How do I start hunting?", "Watch the bounty board, stock pearls, and move before your target expects it.", "hunting"),
                            new DialogueOption("Any survival advice?", "Never chase across open ground without an exit route. Greed gets hunters killed.", "survival"),
                            new DialogueOption("I am ready.", "Then move. Good hunters learn by reading the map, not by standing still.", null)
                        )
                    ),
                    "hunting", new QuestDialogueNode(
                        "hunting",
                        "Start with isolated targets. Confirm their last seen region, then close the gap with a clean plan.",
                        List.of(
                            new DialogueOption("What loot matters most?", "Take mobility, gapples, and proof of the kill. Flashy items come second.", null),
                            new DialogueOption("Back", "You need a different angle? Fine.", "intro")
                        )
                    ),
                    "survival", new QuestDialogueNode(
                        "survival",
                        "Carry only what you can defend. Every extra stack slows your decisions when the fight turns.",
                        List.of(
                            new DialogueOption("What about teammates?", "Assign one scout, one closer, one cleaner. Chaos loses bounty runs.", null),
                            new DialogueOption("Back", "Ask quickly. Targets do not wait.", "intro")
                        )
                    )
                )
            );
        }

        private static QuestDialogueTree scout() {
            return new QuestDialogueTree(
                "intro",
                "Train players to scout routes, watch terrain, and report precise enemy movements.",
                "Sharper map awareness and stronger kingdom callouts.",
                Map.of(
                    "intro", new QuestDialogueNode(
                        "intro",
                        "A scout survives by noticing what everyone else misses. Routes, banners, and fresh blocks tell the real story.",
                        List.of(
                            new DialogueOption("What should I watch first?", "High ground and choke points. Information from elevation beats raw speed.", "routes"),
                            new DialogueOption("How do I report well?", "Be exact. Coordinates, direction, team size, and gear. No vague callouts.", "reporting"),
                            new DialogueOption("That is enough.", "Good. Quiet eyes keep kingdoms alive.", null)
                        )
                    ),
                    "routes", new QuestDialogueNode(
                        "routes",
                        "Mark safe retreat lines before you move in. The best scout already knows where to vanish.",
                        List.of(
                            new DialogueOption("And if I get spotted?", "Fall back through terrain that breaks line of sight, then report immediately.", null),
                            new DialogueOption("Back", "Then keep your head up.", "intro")
                        )
                    ),
                    "reporting", new QuestDialogueNode(
                        "reporting",
                        "A clean report lets your fighters act without hesitation. Precision wins more wars than panic.",
                        List.of(
                            new DialogueOption("Back", "Then keep feeding them facts.", "intro")
                        )
                    )
                )
            );
        }

        private static QuestDialogueTree keeper() {
            return new QuestDialogueTree(
                "intro",
                "Teach the support role: organize recovery supplies, manage revives, and stabilize the team.",
                "Stronger kingdom logistics and recovery discipline.",
                Map.of(
                    "intro", new QuestDialogueNode(
                        "intro",
                        "Kingdoms collapse when nobody manages the quiet work. Storage, revives, and reserves decide long wars.",
                        List.of(
                            new DialogueOption("What does a keeper protect?", "Totems, spare armor, and the discipline to save them for the right moment.", "stores"),
                            new DialogueOption("How do I help my team?", "Keep supplies sorted and make recovery plans before anyone dies.", "support"),
                            new DialogueOption("Understood.", "Good. Stability is a weapon too.", null)
                        )
                    ),
                    "stores", new QuestDialogueNode(
                        "stores",
                        "If your vault is chaos, your fighters will feel it. Label what matters and keep emergency kits untouched.",
                        List.of(
                            new DialogueOption("Back", "Order first, panic never.", "intro")
                        )
                    ),
                    "support", new QuestDialogueNode(
                        "support",
                        "Watch who is low on lives, who needs a revive path, and who is overextending with no backup.",
                        List.of(
                            new DialogueOption("Back", "Then keep them alive.", "intro")
                        )
                    )
                )
            );
        }
    }
}