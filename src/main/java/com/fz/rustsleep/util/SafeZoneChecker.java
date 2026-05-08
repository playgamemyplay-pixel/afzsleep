package com.fz.rustsleep.util;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

public class SafeZoneChecker {

    private final boolean worldGuardAvailable;

    public SafeZoneChecker() {
        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        worldGuardAvailable = (wg != null && wg.isEnabled());
    }

    /**
     * Returns true if the location is inside any WorldGuard protected region.
     * If WorldGuard is not installed, always returns false.
     */
    public boolean isInSafeZone(Location location) {
        if (!worldGuardAvailable || location.getWorld() == null) return false;

        try {
            RegionManager regionManager = WorldGuard.getInstance()
                .getPlatform()
                .getRegionContainer()
                .get(BukkitAdapter.adapt(location.getWorld()));

            if (regionManager == null) return false;

            com.sk89q.worldedit.math.BlockVector3 pos = BukkitAdapter.asBlockVector(location);

            for (ProtectedRegion region : regionManager.getApplicableRegions(pos)) {
                // Any region except __global__ counts as a safe zone
                if (!region.getId().equalsIgnoreCase("__global__")) {
                    return true;
                }
            }
        } catch (Exception e) {
            // WorldGuard error - treat as not in safezone
        }

        return false;
    }

    public boolean isWorldGuardAvailable() {
        return worldGuardAvailable;
    }
}
