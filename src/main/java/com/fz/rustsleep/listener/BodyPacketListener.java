package com.fz.rustsleep.listener;

import com.fz.rustsleep.RustSleep;
import com.fz.rustsleep.npc.SleepingBody;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class BodyPacketListener extends PacketListenerAbstract {

    private final RustSleep plugin;

    public BodyPacketListener(RustSleep plugin) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;

        Player player = (Player) event.getPlayer();
        if (player == null) return;

        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        int entityId = packet.getEntityId();

        if (!plugin.getBodyManager().isBodyEntity(entityId)) return;

        // Cancel packet — we handle it ourselves
        event.setCancelled(true);

        SleepingBody body = plugin.getBodyManager().getBodyByEntityId(entityId);
        if (body == null) return;

        WrapperPlayClientInteractEntity.InteractAction action = packet.getAction();

        if (action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            // Run on main thread — Bukkit API is not thread-safe
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                double damage = calculateDamage(player);
                plugin.getBodyManager().damageBody(entityId, player, damage);
            });

        } else if (action == WrapperPlayClientInteractEntity.InteractAction.INTERACT
                || action == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) {
            // Open inventory on main thread
            plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getBodyManager().openLootInventory(player, body));
        }
    }

    private double calculateDamage(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) return 1.0;

        // Try to get attack damage attribute from item
        try {
            var modifiers = hand.getItemMeta() != null
                ? hand.getItemMeta().getAttributeModifiers(
                    org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE)
                : null;

            if (modifiers != null && !modifiers.isEmpty()) {
                return modifiers.values().iterator().next().getAmount();
            }
        } catch (Exception ignored) {}

        // Fallback: hardcoded weapon damage
        return switch (hand.getType()) {
            case NETHERITE_SWORD -> 8.0;
            case DIAMOND_SWORD   -> 7.0;
            case IRON_SWORD      -> 6.0;
            case STONE_SWORD     -> 5.0;
            case GOLDEN_SWORD,
                 WOODEN_SWORD    -> 4.0;
            case NETHERITE_AXE   -> 10.0;
            case DIAMOND_AXE     -> 9.0;
            case IRON_AXE        -> 9.0;
            case STONE_AXE       -> 9.0;
            case GOLDEN_AXE,
                 WOODEN_AXE      -> 7.0;
            default              -> 1.0;
        };
    }
}
