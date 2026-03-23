package com.beyondminer.npc;

import com.beyondminer.skin.SkinHeadService;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.EulerAngle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FakeNpcEngine implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String INTERACTION_MARKER_KEY = "kingdomsbounty.statue.hitbox";
    private static final String DISPLAY_MARKER_KEY = "kingdomsbounty.statue.display";
    private static final String NPC_ID_KEY = "kingdomsbounty.statue.id";

    private final SkinHeadService skinHeadService;
    private final NamespacedKey interactionMarkerKey;
    private final NamespacedKey displayMarkerKey;
    private final NamespacedKey npcIdKey;
    private final Map<String, NpcState> npcById = new LinkedHashMap<>();
    private final Map<Integer, String> npcIdByEntityId = new ConcurrentHashMap<>();

    public FakeNpcEngine(JavaPlugin plugin, SkinHeadService skinHeadService) {
        this.skinHeadService = skinHeadService;
        this.interactionMarkerKey = new NamespacedKey(plugin, INTERACTION_MARKER_KEY);
        this.displayMarkerKey = new NamespacedKey(plugin, DISPLAY_MARKER_KEY);
        this.npcIdKey = new NamespacedKey(plugin, NPC_ID_KEY);
    }

    public void spawnNpc(String npcId, NpcRequest request) {
        despawnNpc(npcId);

        World world = request.location().getWorld();
        if (world == null) {
            return;
        }

        ArmorStand stand = world.spawn(request.location(), ArmorStand.class, armorStand -> {
            armorStand.setGravity(false);
            armorStand.setInvulnerable(true);
            armorStand.setPersistent(true);
            armorStand.setSilent(true);
            armorStand.setArms(request.hands());
            armorStand.setBasePlate(request.basePlate());
            armorStand.setVisible(!request.invisible());
            armorStand.setCanPickupItems(false);
            armorStand.setCustomNameVisible(false);
            armorStand.setGlowing(request.glow());
            applyNonCollidable(armorStand);
            armorStand.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, npcId);

            EntityEquipment equipment = armorStand.getEquipment();
            if (equipment != null) {
                equipment.setHelmet(createHead(request));
                equipment.setChestplate(createArmorPiece(Material.DIAMOND_CHESTPLATE));
                equipment.setLeggings(createArmorPiece(Material.DIAMOND_LEGGINGS));
                equipment.setBoots(createArmorPiece(Material.DIAMOND_BOOTS));
            }
        });
        applyPose(stand, request.location(), request.pose());

        Interaction interaction = world.spawn(request.location().clone().add(0.0D, 0.9D, 0.0D), Interaction.class, hitbox -> {
            hitbox.setGravity(false);
            hitbox.setInvulnerable(true);
            hitbox.setPersistent(true);
            hitbox.setSilent(true);
            hitbox.setInteractionWidth(0.85f);
            hitbox.setInteractionHeight(2.0f);
            hitbox.getPersistentDataContainer().set(interactionMarkerKey, PersistentDataType.BYTE, (byte) 1);
            hitbox.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, npcId);
        });

        TextDisplay nameDisplay = createLabel(npcId, request.location().clone().add(0.0D, 2.35D, 0.0D), request.displayName(), request.nameVisible());
        TextDisplay subtitleDisplay = createLabel(
                npcId,
                request.location().clone().add(0.0D, 2.08D, 0.0D),
                request.subtitle(),
                request.nameVisible() && request.subtitle() != null && !request.subtitle().isBlank()
        );

        NpcState state = new NpcState(
                npcId,
                request.location().clone(),
                stand,
                interaction,
                nameDisplay,
                subtitleDisplay,
                request.pose(),
                request.glow(),
                request.invisible(),
                request.basePlate(),
                request.hands()
        );
        npcById.put(npcId, state);
        trackEntity(npcId, stand);
        trackEntity(npcId, interaction);
        trackEntity(npcId, nameDisplay);
        trackEntity(npcId, subtitleDisplay);
    }

    public void despawnNpc(String npcId) {
        NpcState state = npcById.remove(npcId);
        if (state == null) {
            return;
        }

        untrackEntity(state.stand());
        untrackEntity(state.interaction());
        untrackEntity(state.nameDisplay());
        untrackEntity(state.subtitleDisplay());
        removeEntity(state.stand());
        removeEntity(state.interaction());
        removeEntity(state.nameDisplay());
        removeEntity(state.subtitleDisplay());
    }

    public void despawnAllWithPrefix(String prefix) {
        List<String> ids = new ArrayList<>();
        for (String id : npcById.keySet()) {
            if (id.startsWith(prefix)) {
                ids.add(id);
            }
        }
        ids.forEach(this::despawnNpc);
    }

    public void despawnAll() {
        new ArrayList<>(npcById.keySet()).forEach(this::despawnNpc);
    }

    public void updateNpcPose(String npcId, NpcPose pose) {
        NpcState state = npcById.get(npcId);
        if (state == null || pose == null) {
            return;
        }

        applyPose(state.stand(), state.location(), pose);
        npcById.put(npcId, new NpcState(
                state.id(),
                state.location(),
                state.stand(),
                state.interaction(),
                state.nameDisplay(),
                state.subtitleDisplay(),
                pose,
                state.glowing(),
                state.invisible(),
                state.basePlate(),
                state.hands()
        ));
    }

    public boolean isNpcEntity(Entity entity) {
        return getNpcId(entity) != null;
    }

    public String getNpcId(Entity entity) {
        if (entity == null) {
            return null;
        }

        String mapped = npcIdByEntityId.get(entity.getEntityId());
        if (mapped != null) {
            return mapped;
        }

        return entity.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
    }

    private ItemStack createHead(NpcRequest request) {
        if (skinHeadService == null) {
            return new ItemStack(Material.PLAYER_HEAD);
        }
        if (request.textureValue() != null && !request.textureValue().isBlank()) {
            return skinHeadService.createHeadFromTextureData(request.textureValue(), null);
        }

        String lookup = request.profileName() == null || request.profileName().isBlank() ? "Steve" : request.profileName();
        return skinHeadService.createHead(lookup, null);
    }

    private ItemStack createArmorPiece(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyPose(ArmorStand stand, Location location, NpcPose pose) {
        if (stand == null || !stand.isValid()) {
            return;
        }

        stand.teleport(location.clone());
        stand.setRotation(location.getYaw() + pose.bodyYawOffset(), 0.0f);
        stand.setHeadPose(new EulerAngle(Math.toRadians(pose.pitchOffset()), Math.toRadians(pose.headYawOffset()), 0.0D));
        stand.setBodyPose(EulerAngle.ZERO);
        stand.setLeftArmPose(pose.leftArmPose());
        stand.setRightArmPose(pose.rightArmPose());
        stand.setLeftLegPose(EulerAngle.ZERO);
        stand.setRightLegPose(EulerAngle.ZERO);
    }

    private TextDisplay createLabel(String npcId, Location location, String rawText, boolean visible) {
        if (!visible || rawText == null || rawText.isBlank() || location.getWorld() == null) {
            return null;
        }

        return location.getWorld().spawn(location, TextDisplay.class, display -> {
            display.text(LEGACY.deserialize(rawText));
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(true);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setSilent(true);
            display.setSeeThrough(false);
            display.setShadowed(false);
            display.setDefaultBackground(false);
            display.getPersistentDataContainer().set(displayMarkerKey, PersistentDataType.BYTE, (byte) 1);
            display.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, npcId);
        });
    }

    private void applyNonCollidable(ArmorStand armorStand) {
        try {
            Method method = armorStand.getClass().getMethod("setCollidable", boolean.class);
            method.invoke(armorStand, false);
        } catch (Exception ignored) {
        }
    }

    private void trackEntity(String npcId, Entity entity) {
        if (entity != null) {
            npcIdByEntityId.put(entity.getEntityId(), npcId);
        }
    }

    private void untrackEntity(Entity entity) {
        if (entity != null) {
            npcIdByEntityId.remove(entity.getEntityId());
        }
    }

    private void removeEntity(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    public enum NpcPose {
        DEFAULT(0.0f, 0.0f, EulerAngle.ZERO, EulerAngle.ZERO, 0.0f),
        CROUCH(8.0f, 0.0f, EulerAngle.ZERO, EulerAngle.ZERO, 0.0f),
        LOOK_LEFT(0.0f, -35.0f, EulerAngle.ZERO, EulerAngle.ZERO, 0.0f),
        LOOK_RIGHT(0.0f, 35.0f, EulerAngle.ZERO, EulerAngle.ZERO, 0.0f),
        BOW(20.0f, 0.0f, new EulerAngle(Math.toRadians(-95.0D), 0.0D, 0.0D), new EulerAngle(Math.toRadians(-95.0D), 0.0D, 0.0D), 0.0f),
        THINKER(12.0f, -12.0f, new EulerAngle(Math.toRadians(-25.0D), 0.0D, Math.toRadians(-12.0D)), new EulerAngle(Math.toRadians(-75.0D), 0.0D, Math.toRadians(22.0D)), 0.0f),
        SALUTE(-8.0f, 8.0f, EulerAngle.ZERO, new EulerAngle(Math.toRadians(-135.0D), 0.0D, Math.toRadians(20.0D)), 0.0f);

        private final float pitchOffset;
        private final float headYawOffset;
        private final EulerAngle leftArmPose;
        private final EulerAngle rightArmPose;
        private final float bodyYawOffset;

        NpcPose(float pitchOffset, float headYawOffset, EulerAngle leftArmPose, EulerAngle rightArmPose, float bodyYawOffset) {
            this.pitchOffset = pitchOffset;
            this.headYawOffset = headYawOffset;
            this.leftArmPose = leftArmPose;
            this.rightArmPose = rightArmPose;
            this.bodyYawOffset = bodyYawOffset;
        }

        public float pitchOffset() {
            return pitchOffset;
        }

        public float headYawOffset() {
            return headYawOffset;
        }

        public EulerAngle leftArmPose() {
            return leftArmPose;
        }

        public EulerAngle rightArmPose() {
            return rightArmPose;
        }

        public float bodyYawOffset() {
            return bodyYawOffset;
        }
    }

    public record NpcRequest(
            Location location,
            String profileName,
            String displayName,
            String subtitle,
            String textureValue,
            String textureSignature,
            boolean nameVisible,
            boolean glow,
            NpcPose pose,
            boolean invisible,
                boolean basePlate,
                boolean hands
    ) {
    }

    private record NpcState(
            String id,
            Location location,
            ArmorStand stand,
            Interaction interaction,
            TextDisplay nameDisplay,
            TextDisplay subtitleDisplay,
            NpcPose pose,
            boolean glowing,
            boolean invisible,
            boolean basePlate,
            boolean hands
    ) {
    }
}
