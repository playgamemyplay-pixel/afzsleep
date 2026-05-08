package com.fz.rustsleep.listener;

import com.fz.rustsleep.RustSleep;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.entity.Player;

public class PlayerConnectionListener implements Listener {

    private final RustSleep plugin;

    public PlayerConnectionListener(RustSleep plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Track session start
        plugin.getBodyManager().onPlayerJoin(player);

        // Send all existing bodies to this player
        plugin.getBodyManager().sendBodiesToPlayer(player);

        // Remove their own body if it exists (they reconnected)
        if (plugin.getBodyManager().hasBody(player.getUniqueId())) {
            plugin.getBodyManager().removeBody(player.getUniqueId(), player);
        } else {
            // Check DB for killed body
            plugin.getBodyManager().removeBody(player.getUniqueId(), player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Save session playtime
        plugin.getBodyManager().onPlayerQuit(player);

        // Spawn sleeping body
        plugin.getBodyManager().spawnBody(player);
    }
}
