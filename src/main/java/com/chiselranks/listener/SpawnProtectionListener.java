package com.chiselranks.listener;

import com.chiselranks.ChiselRanksPlugin;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockCanBuildEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SpawnProtectionListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MILLIS = 1500L;

    private final ChiselRanksPlugin plugin;
    private final SpawnFlightListener spawnFlightListener;
    private final Map<UUID, Long> messageCooldowns = new HashMap<>();

    public SpawnProtectionListener(ChiselRanksPlugin plugin, SpawnFlightListener spawnFlightListener) {
        this.plugin = plugin;
        this.spawnFlightListener = spawnFlightListener;
    }

    public boolean shouldBlockOperators() {
        return plugin.getConfig().getBoolean("spawn-flight.block-ops", true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (canBypassProtection(event.getPlayer())) {
            return;
        }
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
            sendAccessMessage(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (canBypassProtection(event.getPlayer())) {
            return;
        }
        if (isProtected(event.getBlockPlaced()) || isProtected(event.getBlockAgainst())) {
            event.setCancelled(true);
            sendAccessMessage(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (canBypassProtection(event.getPlayer())) {
            return;
        }
        Block targetBlock = event.getBlockClicked().getRelative(event.getBlockFace());
        if (isProtected(targetBlock)) {
            event.setCancelled(true);
            sendAccessMessage(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (canBypassProtection(event.getPlayer())) {
            return;
        }
        Block source = event.getBlockClicked().getRelative(event.getBlockFace());
        if (isProtected(source) || isProtected(event.getBlockClicked())) {
            event.setCancelled(true);
            sendAccessMessage(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (canBypassProtection(event.getPlayer())) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (!isProtected(clicked)) {
            return;
        }

        BlockState state = clicked.getState();
        if (state instanceof InventoryHolder || clicked.getType().isInteractable()) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);
            sendAccessMessage(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (canBypassProtection(player)) {
            return;
        }

        Location inventoryLocation = event.getInventory().getLocation();
        if (!isProtected(inventoryLocation)) {
            return;
        }

        event.setCancelled(true);
        sendAccessMessage(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = null;
        if (event.getDamager() instanceof Player player) {
            attacker = player;
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            attacker = player;
        }

        if (attacker != null) {
            if (isProtected(victim.getLocation()) || isProtected(attacker.getLocation())) {
                event.setCancelled(true);
            }
            return;
        }

        boolean mobAttack = event.getDamager() instanceof Mob
                || (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Mob);
        if (mobAttack && isProtected(victim.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Mob && isProtected(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Mob)) {
            return;
        }
        if (!(event.getTarget() instanceof Player player)) {
            return;
        }
        if (isProtected(player.getLocation()) || isProtected(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getSourceBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getNewState().getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getNewState().getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getNewState().getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getSource()) || isProtected(event.getNewState().getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFlow(BlockFromToEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCanBuild(BlockCanBuildEvent event) {
        if (!shouldBlockOperators()) {
            return;
        }
        if (isProtected(event.getBlock())) {
            event.setBuildable(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMoistureChange(MoistureChangeEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getNewState().getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityBlockForm(EntityBlockFormEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getNewState().getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
            return;
        }

        event.getBlocks().removeIf(state -> isProtected(state.getBlock()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCauldronLevelChange(CauldronLevelChangeEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
            return;
        }

        for (Block movedBlock : event.getBlocks()) {
            if (isProtected(movedBlock) || isProtected(movedBlock.getRelative(event.getDirection()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
            return;
        }

        for (Block movedBlock : event.getBlocks()) {
            Block destination = movedBlock.getRelative(opposite(event.getDirection()));
            if (isProtected(movedBlock) || isProtected(destination)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getBlocks().stream().anyMatch(state -> isProtected(state.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (event.getBlocks().stream().anyMatch(state -> isProtected(state.getBlock()))) {
            event.setCancelled(true);
        }
    }

    private boolean isProtected(Block block) {
        return block != null && isProtected(block.getLocation());
    }

    private boolean isProtected(Location location) {
        if (!spawnFlightListener.isInConfiguredRegion(location)) {
            return false;
        }

        if (plugin == null) {
            return false;
        }

        return true;
    }

    private boolean canBypassProtection(Player player) {
        return player != null && player.isOp() && !shouldBlockOperators();
    }

    private void sendAccessMessage(Player player) {
        if (player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long lastSent = messageCooldowns.get(player.getUniqueId());
        if (lastSent != null && now - lastSent < MESSAGE_COOLDOWN_MILLIS) {
            return;
        }

        messageCooldowns.put(player.getUniqueId(), now);
        player.sendMessage(plugin.message("messages.spawnfly-access-blocks"));
    }

    private BlockFace opposite(BlockFace face) {
        return face == null ? BlockFace.SELF : face.getOppositeFace();
    }
}