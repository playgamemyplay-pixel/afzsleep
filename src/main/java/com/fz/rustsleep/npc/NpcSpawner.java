package com.fz.rustsleep.npc;

import com.fz.rustsleep.RustSleep;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.*;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class NpcSpawner {

    private static final AtomicInteger ID_COUNTER = new AtomicInteger(500000);
    private final RustSleep plugin;

    public NpcSpawner(RustSleep plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------
    // PUBLIC API
    // -----------------------------------------------

    public void spawnForAll(SleepingBody body) {
        if (body.getNpcEntityId() == -1) {
            body.setNpcEntityId(ID_COUNTER.getAndIncrement());
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Don't spawn the body to the owner themselves
            if (!p.getUniqueId().equals(body.getOwnerUUID())) {
                spawnForPlayer(body, p);
            }
        }
    }

    public void spawnForPlayer(SleepingBody body, Player viewer) {
        if (viewer.getUniqueId().equals(body.getOwnerUUID())) return;
        if (body.getNpcEntityId() == -1) return;

        // Step 1: Add to tab list
        sendTabAdd(body, viewer);

        // Step 2: Spawn entity
        Location loc = body.getLocation();
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
            body.getNpcEntityId(),
            Optional.of(body.getNpcUUID()),
            EntityTypes.PLAYER,
            new Vector3d(loc.getX(), loc.getY(), loc.getZ()),
            0f, loc.getYaw(), loc.getYaw(),
            0,
            Optional.of(new Vector3d(0, 0, 0))
        );
        send(viewer, spawn);

        // Step 3: Metadata
        sendMetadata(body, viewer, false);

        // Step 4: Equipment
        sendEquipment(body, viewer);

        // Step 5: Remove from tab after 1 second
        Bukkit.getScheduler().runTaskLater(plugin, () -> sendTabRemove(body, viewer), 20L);
    }

    public void despawnForAll(SleepingBody body) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            despawnForPlayer(body, p);
        }
    }

    public void despawnForPlayer(SleepingBody body, Player viewer) {
        if (body.getNpcEntityId() == -1) return;
        send(viewer, new WrapperPlayServerDestroyEntities(body.getNpcEntityId()));
        sendTabRemove(body, viewer);
    }

    public void updateNameTag(SleepingBody body, Player viewer, boolean visible) {
        if (body.getNpcEntityId() == -1) return;
        sendMetadata(body, viewer, visible);
    }

    public void updateHealthForAll(SleepingBody body) {
        double dist = plugin.getConfig().getDouble("settings.name-visible-distance", 8.0);
        double distSq = dist * dist;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(body.getOwnerUUID())) continue;

            boolean nearby = p.getWorld().equals(body.getLocation().getWorld())
                && p.getLocation().distanceSquared(body.getLocation()) <= distSq;

            sendMetadata(body, p, nearby);
        }
    }

    // -----------------------------------------------
    // PRIVATE HELPERS
    // -----------------------------------------------

    private void sendTabAdd(SleepingBody body, Player viewer) {
        List<TextureProperty> textures = new ArrayList<>();

        if (body.getSkinTexture() != null && body.getSkinSignature() != null) {
            textures.add(new TextureProperty(
                "textures",
                body.getSkinTexture(),
                body.getSkinSignature()
            ));
        }

        UserProfile profile = new UserProfile(
            body.getNpcUUID(),
            body.getOwnerName(),
            textures
        );

        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
            new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile,
                true,
                0,
                GameMode.SURVIVAL,
                Component.text(body.getOwnerName()),
                null
            );

        send(viewer, new WrapperPlayServerPlayerInfoUpdate(
            EnumSet.of(
                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED
            ),
            Collections.singletonList(info)
        ));
    }

    private void sendTabRemove(SleepingBody body, Player viewer) {
        send(viewer, new WrapperPlayServerPlayerInfoRemove(
            Collections.singletonList(body.getNpcUUID())
        ));
    }

    private void sendMetadata(SleepingBody body, Player viewer, boolean nameVisible) {
        List<EntityData> metadata = new ArrayList<>();

        String nameStr = buildNameString(body);

        metadata.add(new EntityData(
            2,
            EntityDataTypes.OPTIONAL_COMPONENT,
            Optional.of(Component.text(nameStr))
        ));

        metadata.add(new EntityData(
            3,
            EntityDataTypes.BOOLEAN,
            nameVisible
        ));

        metadata.add(new EntityData(
            6,
            EntityDataTypes.ENTITY_POSE,
            EntityPose.SLEEPING
        ));

        metadata.add(new EntityData(
            17,
            EntityDataTypes.BYTE,
            (byte) 0x7F
        ));

        send(viewer, new WrapperPlayServerEntityMetadata(
            body.getNpcEntityId(),
            metadata
        ));
    }

    private String buildNameString(SleepingBody body) {
        String hearts = "\u2764";

        int hp = (int) body.getHealth();
        int maxHp = (int) body.getMaxHealth();

        if (body.isSafezoneProtected()) {
            long mins = body.getRemainingProtectionMinutes();
            long secs = ((body.getSafezoneExpiry() - System.currentTimeMillis()) / 1000L) % 60;

            return String.format(
                "\u00A7c%s \u00A77%s %d/%d \u00A7e[Protected %dm %ds]",
                body.getOwnerName(),
                hearts,
                hp,
                maxHp,
                mins,
                secs
            );
        }

        String hpColor =
            hp > maxHp * 0.6 ? "\u00A7a" :
            hp > maxHp * 0.3 ? "\u00A7e" :
            "\u00A7c";

        return String.format(
            "\u00A7f%s %s%s %d/%d",
            body.getOwnerName(),
            hpColor,
            hearts,
            hp,
            maxHp
        );
    }

    private void sendEquipment(SleepingBody body, Player viewer) {
        List<Equipment> list = new ArrayList<>();

        addEquip(list, EquipmentSlot.HELMET, body.getHelmet());
        addEquip(list, EquipmentSlot.CHEST, body.getChestplate());
        addEquip(list, EquipmentSlot.LEGGINGS, body.getLeggings());
        addEquip(list, EquipmentSlot.BOOTS, body.getBoots());
        addEquip(list, EquipmentSlot.OFF_HAND, body.getOffhand());

        ItemStack[] inv = body.getContents();

        if (inv != null && inv.length > 0 && inv[0] != null) {
            addEquip(list, EquipmentSlot.MAIN_HAND, inv[0]);
        }

        if (!list.isEmpty()) {
            send(viewer, new WrapperPlayServerEntityEquipment(
                body.getNpcEntityId(),
                list
            ));
        }
    }

    private void addEquip(List<Equipment> list, EquipmentSlot slot, ItemStack item) {
        if (item != null && item.getType() != org.bukkit.Material.AIR) {
            list.add(new Equipment(
                slot,
                SpigotConversionUtil.fromBukkitItemStack(item)
            ));
        }
    }

    private void send(Player player, Object packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        } catch (Exception ignored) {
        }
    }
}
