package com.fz.rustsleep.listener;

import com.fz.rustsleep.RustSleep;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;

public class InventoryListener implements Listener {

    private final RustSleep plugin;

    public InventoryListener(RustSleep plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player looter)) return;

        // Check if this looter has a body inventory open
        if (plugin.getBodyManager().getOwnerByLooter(looter) == null) return;

        // Only handle clicks in the top (body) inventory, not the player's own
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory().equals(looter.getInventory())) return;

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        int slot = event.getSlot();

        // Register the theft
        plugin.getBodyManager().onLooterClickItem(looter, slot, item);
        // Allow the item to transfer naturally to player
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player looter)) return;
        plugin.getBodyManager().onLooterClose(looter);
    }
}
