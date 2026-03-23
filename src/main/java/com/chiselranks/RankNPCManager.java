package com.chiselranks;

import com.beyondminer.npc.FakeNpcEngine;
import com.beyondminer.npc.StatueSkinService;
import com.beyondminer.skin.SkinHeadService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

public final class RankNPCManager implements CommandExecutor, Listener {
    private static final String ADMIN_PERMISSION = "chiselranks.admin.spawnnpc";
    private static final String NPC_ID = "rankshop:main";

    private final ChiselRanksPlugin plugin;
    private final RankGUI rankGUI;
    private final FakeNpcEngine npcEngine;
    private final StatueSkinService skinService;

    public RankNPCManager(ChiselRanksPlugin plugin, RankGUI rankGUI, SkinHeadService skinHeadService) {
        this.plugin = plugin;
        this.rankGUI = rankGUI;
        this.npcEngine = new FakeNpcEngine(plugin, skinHeadService);
        this.skinService = new StatueSkinService(plugin, skinHeadService);
        plugin.getServer().getPluginManager().registerEvents(this.npcEngine, plugin);
    }

    public void loadNpc() {
        removeTrackedNpcs();
        if (!plugin.getConfig().getBoolean("rank-npc.enabled", false)) {
            return;
        }

        String worldName = plugin.getConfig().getString("rank-npc.world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Configured rank NPC world is missing: " + worldName);
            return;
        }

        Location location = new Location(
                world,
                plugin.getConfig().getDouble("rank-npc.x"),
                plugin.getConfig().getDouble("rank-npc.y"),
                plugin.getConfig().getDouble("rank-npc.z"),
                (float) plugin.getConfig().getDouble("rank-npc.yaw"),
                (float) plugin.getConfig().getDouble("rank-npc.pitch")
        );
        String skinName = plugin.getConfig().getString("rank-npc.skin", "Steve");
        spawnNpc(location, skinName, true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.message("messages.spawnnpc-op-only"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(plugin.message("messages.spawnnpc-usage"));
            return true;
        }

        Location location = player.getLocation().clone().add(player.getLocation().getDirection().normalize().multiply(1.5D));
        location.setYaw(player.getLocation().getYaw() - 180.0f);
        location.setPitch(0.0f);
        spawnNpc(location, args[0], false);
        sender.sendMessage(plugin.message("messages.spawnnpc-created")
                .replace("%skin%", args[0])
                .replace("%world%", location.getWorld() == null ? "unknown" : location.getWorld().getName()));
        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (!isRankNpc(event.getRightClicked())) {
            return;
        }

        event.setCancelled(true);
        rankGUI.open(event.getPlayer());
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (isRankNpc(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private void spawnNpc(Location location, String skinName, boolean fromConfig) {
        removeTrackedNpcs();
        if (location.getWorld() == null) {
            return;
        }

        skinService.resolvePremium(skinName)
                .exceptionally(ignored -> null)
                .thenAccept(resolvedSkin -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (location.getWorld() == null) {
                        return;
                    }

                    String textureValue = resolvedSkin == null ? "" : resolvedSkin.value();
                    String textureSignature = resolvedSkin == null ? "" : resolvedSkin.signature();
                    npcEngine.spawnNpc(NPC_ID, new FakeNpcEngine.NpcRequest(
                            location,
                            skinName,
                            "&6Ranks",
                            "&7Browse rank upgrades",
                            textureValue,
                            textureSignature,
                            true,
                            false,
                            FakeNpcEngine.NpcPose.DEFAULT,
                            false,
                            false,
                            true
                    ));
                }));

        if (!fromConfig) {
            plugin.getConfig().set("rank-npc.enabled", true);
            plugin.getConfig().set("rank-npc.world", location.getWorld().getName());
            plugin.getConfig().set("rank-npc.x", location.getX());
            plugin.getConfig().set("rank-npc.y", location.getY());
            plugin.getConfig().set("rank-npc.z", location.getZ());
            plugin.getConfig().set("rank-npc.yaw", location.getYaw());
            plugin.getConfig().set("rank-npc.pitch", location.getPitch());
            plugin.getConfig().set("rank-npc.skin", skinName);
            plugin.saveConfig();
        }
    }

    private void removeTrackedNpcs() {
        npcEngine.despawnNpc(NPC_ID);
    }

    private boolean isRankNpc(Entity entity) {
        return NPC_ID.equals(npcEngine.getNpcId(entity));
    }
}