package com.fz.rustsleep;

import com.fz.rustsleep.database.DatabaseManager;
import com.fz.rustsleep.listener.*;
import com.fz.rustsleep.manager.BodyManager;
import com.fz.rustsleep.util.SafeZoneChecker;
import com.fz.rustsleep.util.SkinFetcher;
import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.plugin.java.JavaPlugin;

public class RustSleep extends JavaPlugin {

    private static RustSleep instance;
    private DatabaseManager database;
    private BodyManager bodyManager;
    private SafeZoneChecker safeZoneChecker;
    private SkinFetcher skinFetcher;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Init PacketEvents
        PacketEvents.getAPI().init();

        // Init database
        database = new DatabaseManager(this);
        database.init();

        // Init utilities
        safeZoneChecker = new SafeZoneChecker();
        skinFetcher     = new SkinFetcher(this);

        // Init body manager
        bodyManager = new BodyManager(this, safeZoneChecker, skinFetcher);

        // Register Bukkit listeners
        getServer().getPluginManager().registerEvents(
            new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(
            new InventoryListener(this), this);

        // Register PacketEvents listener (for attack + interact intercept)
        PacketEvents.getAPI().getEventManager()
            .registerListener(new BodyPacketListener(this));

        // Load all saved bodies from database (after server restart)
        bodyManager.loadBodiesFromDatabase();

        getLogger().info("RustSleep v3.0.0 enabled successfully!");
        getLogger().info("WorldGuard: " + (safeZoneChecker.isWorldGuardAvailable() ? "yes" : "no"));
    }

    @Override
    public void onDisable() {
        if (bodyManager != null) bodyManager.removeAllBodies();
        if (database   != null) database.close();
        PacketEvents.getAPI().terminate();
        getLogger().info("RustSleep disabled.");
    }

    // ---- Helpers ----

    public static RustSleep getInstance()     { return instance; }
    public DatabaseManager getDatabase()      { return database; }
    public BodyManager getBodyManager()       { return bodyManager; }
    public SafeZoneChecker getSafeZone()      { return safeZoneChecker; }

    public static String color(String s) {
        return s.replace("&", "\u00A7");
    }

    public String prefix() {
        return color(getConfig().getString("messages.prefix", "&8[&6RustSleep&8] &r"));
    }

    public String msg(String key) {
        return color(getConfig().getString("messages." + key, "&cMissing key: " + key));
    }

    public String msg(String key, String... replacements) {
        String msg = msg(key);
        for (int i = 0; i + 1 < replacements.length; i += 2)
            msg = msg.replace(replacements[i], replacements[i + 1]);
        return msg;
    }
}
