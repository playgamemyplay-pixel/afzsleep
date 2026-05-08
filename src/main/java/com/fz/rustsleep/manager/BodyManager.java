package com.fz.rustsleep.manager;

import com.fz.rustsleep.RustSleep;
import com.fz.rustsleep.database.DatabaseManager;
import com.fz.rustsleep.database.DatabaseManager.BodyData;
import com.fz.rustsleep.database.DatabaseManager.InventoryData;
import com.fz.rustsleep.npc.NpcSpawner;
import com.fz.rustsleep.npc.SleepingBody;
import com.fz.rustsleep.util.SafeZoneChecker;
import com.fz.rustsleep.util.SkinFetcher;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BodyManager {

    private final RustSleep plugin;
    private final NpcSpawner npcSpawner;
    private final SafeZoneChecker safeZoneChecker;
    private final SkinFetcher skinFetcher;

    // ownerUUID -> body
    private final Map<UUID, SleepingBody> bodies = new ConcurrentHashMap<>();
    // entityId -> ownerUUID
    private final Map<Integer, UUID> entityIdMap = new ConcurrentHashMap<>();
    // looterUUID -> ownerUUID
    private final Map<UUID, UUID> openInventories = new ConcurrentHashMap<>();

    // playtime tracking: UUID -> join time millis
    private final Map<UUID, Long> sessionStart = new HashMap<>();

    // task IDs
    private int nameTagTaskId = -1;
    private int safezoneExpireTaskId = -1;

    public BodyManager(RustSleep plugin, SafeZoneChecker safeZoneChecker, SkinFetcher skinFetcher) {
        this.plugin          = plugin;
        this.safeZoneChecker = safeZoneChecker;
        this.skinFetcher     = skinFetcher;
        this.npcSpawner      = new NpcSpawner(plugin);

        startNameTagTask();
        startSafezoneExpireTask();
    }

    // ================================================
    //  SESSION TRACKING
    // ================================================

    public void onPlayerJoin(Player player) {
        sessionStart.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void onPlayerQuit(Player player) {
        Long start = sessionStart.remove(player.getUniqueId());
        if (start != null) {
            double minutes = (System.currentTimeMillis() - start) / 60000.0;
            plugin.getDatabase().updatePlaytime(player.getUniqueId(), player.getName(), minutes);
        }
    }

    // ================================================
    //  SPAWN BODY
    // ================================================

    public void spawnBody(Player player) {
        UUID uuid = player.getUniqueId();

        // --- Check 1: minimum playtime ---
        double minPlaytime = plugin.getConfig().getDouble("settings.min-playtime-minutes", 30);
        double totalPlaytime = plugin.getDatabase().getPlaytimeMinutes(uuid);
        // Add current session time
        Long start = sessionStart.get(uuid);
        if (start != null) {
            totalPlaytime += (System.currentTimeMillis() - start) / 60000.0;
        }

        if (totalPlaytime < minPlaytime) {
            plugin.getLogger().info("No body for " + player.getName()
                + " (playtime: " + String.format("%.1f", totalPlaytime) + " min < " + minPlaytime + ")");
            return;
        }

        // --- Check 2: minimum items ---
        int minItems = plugin.getConfig().getInt("settings.min-items-to-spawn", 5);
        int itemCount = countItems(player);

        if (itemCount <= minItems) {
            plugin.getLogger().info("No body for " + player.getName()
                + " (items: " + itemCount + " <= " + minItems + ")");
            return;
        }

        // --- All checks passed: create body ---
        SleepingBody body = new SleepingBody(player);

        // Check if in safe zone
        boolean inSafezone = safeZoneChecker.isInSafeZone(player.getLocation());
        body.setInSafezone(inSafezone);
        if (inSafezone) {
            long protectionMinutes = plugin.getConfig().getLong("settings.safezone-protection-minutes", 30);
            body.setSafezoneExpiry(System.currentTimeMillis() + (protectionMinutes * 60000L));
            plugin.getLogger().info(player.getName() + " disconnected in safe zone - protected for "
                + protectionMinutes + " min");
        }

        bodies.put(uuid, body);

        // Clear player inventory — body holds it now
        player.getInventory().clear();

        // Fetch skin then save to DB and spawn NPC
        skinFetcher.fetchSkin(body, player, () -> {
            // Save to DB
            saveBodyToDatabase(body);

            // Spawn NPC for all online players
            npcSpawner.spawnForAll(body);
            entityIdMap.put(body.getNpcEntityId(), uuid);

            plugin.getLogger().info("Body spawned for " + player.getName()
                + (inSafezone ? " [SAFEZONE]" : ""));
        });
    }

    // ================================================
    //  REMOVE BODY (player returns)
    // ================================================

    public void removeBody(UUID ownerUUID, Player returningPlayer) {
        SleepingBody body = bodies.remove(ownerUUID);
        if (body == null) {
            // Check if killed while offline (only in DB)
            handleReturnFromDb(ownerUUID, returningPlayer);
            return;
        }

        // Close inventories for all looters
        closeAllLooters(body, false);

        // Despawn NPC
        npcSpawner.despawnForAll(body);
        entityIdMap.remove(body.getNpcEntityId());

        // Delete from DB
        plugin.getDatabase().deleteBody(ownerUUID);

        if (returningPlayer != null) {
            if (body.wasKilled()) {
                handleDeath(returningPlayer, body.getKillerName());
            } else {
                body.restoreInventory(returningPlayer);
            }
        }
    }

    private void handleReturnFromDb(UUID uuid, Player player) {
        if (player == null) return;
        // Load from DB to check if killed
        List<BodyData> all = plugin.getDatabase().loadAllBodies();
        for (BodyData d : all) {
            if (d.uuid.equals(uuid)) {
                plugin.getDatabase().deleteBody(uuid);
                if (d.killed && d.killerName != null) {
                    handleDeath(player, d.killerName);
                } else {
                    // Restore inventory from DB
                    InventoryData inv = plugin.getDatabase().loadInventory(uuid);
                    SleepingBody tmp = new SleepingBody(uuid, d.playerName,
                        null, d.health, d.maxHealth, false, 0,
                        null, null, inv);
                    tmp.restoreInventory(player);
                }
                return;
            }
        }
    }

    // ================================================
    //  DAMAGE
    // ================================================

    public void damageBody(int entityId, Player attacker, double damage) {
        UUID ownerUUID = entityIdMap.get(entityId);
        if (ownerUUID == null) return;

        SleepingBody body = bodies.get(ownerUUID);
        if (body == null) return;

        // No damage in safe zone while protected
        if (body.isSafezoneProtected()) {
            long mins = body.getRemainingProtectionMinutes();
            attacker.sendMessage(plugin.prefix()
                + plugin.msg("cant-loot-safezone", "{time}", String.valueOf(mins)));
            return;
        }

        double newHealth = body.getHealth() - damage;

        if (newHealth <= 0) {
            killBody(body, attacker);
        } else {
            body.setHealth(newHealth);
            plugin.getDatabase().updateHealth(ownerUUID, newHealth);
            npcSpawner.updateHealthForAll(body);
        }
    }

    private void killBody(SleepingBody body, Player killer) {
        String killerName = killer != null ? killer.getName() : "Unknown";

        body.setKilled(true);
        body.setKillerName(killerName);

        // Drop all items
        body.dropAllItems();

        // Mark in DB
        plugin.getDatabase().markKilled(body.getOwnerUUID(), killerName);

        // Broadcast
        Bukkit.broadcastMessage(plugin.msg("body-killed",
            "{player}", body.getOwnerName(),
            "{killer}", killerName));

        // Close looters
        closeAllLooters(body, true);

        // Despawn NPC
        npcSpawner.despawnForAll(body);
        entityIdMap.remove(body.getNpcEntityId());
        bodies.remove(body.getOwnerUUID());
    }

    // ================================================
    //  LOOTING
    // ================================================

    public void openLootInventory(Player looter, SleepingBody body) {
        if (looter.getUniqueId().equals(body.getOwnerUUID())) {
            looter.sendMessage(plugin.prefix() + plugin.msg("cant-loot-self"));
            return;
        }

        if (body.isSafezoneProtected()) {
            long mins = body.getRemainingProtectionMinutes();
            looter.sendMessage(plugin.prefix()
                + plugin.msg("cant-loot-safezone", "{time}", String.valueOf(mins)));
            return;
        }

        ItemStack[] inv = body.getContents();
        Inventory gui = Bukkit.createInventory(null, 36,
            RustSleep.color("\u00A74\u2620 \u00A7c" + body.getOwnerName() + "'s Body"));

        for (int i = 0; i < Math.min(inv.length, 36); i++) {
            if (inv[i] != null) gui.setItem(i, inv[i].clone());
        }

        looter.openInventory(gui);
        openInventories.put(looter.getUniqueId(), body.getOwnerUUID());
        body.addLooter(looter.getUniqueId());
        looter.sendMessage(plugin.prefix()
            + plugin.msg("looting", "{player}", body.getOwnerName()));
    }

    public void onLooterClickItem(Player looter, int slot, ItemStack item) {
        UUID ownerUUID = openInventories.get(looter.getUniqueId());
        if (ownerUUID == null) return;

        SleepingBody body = bodies.get(ownerUUID);
        if (body == null) return;

        body.takeItem(slot);
        plugin.getDatabase().deleteInventorySlot(ownerUUID, slot);

        looter.sendMessage(plugin.prefix() + plugin.msg("stolen",
            "{amount}", String.valueOf(item.getAmount()),
            "{item}", formatName(item.getType().name())));
    }

    public void onLooterClose(Player looter) {
        UUID ownerUUID = openInventories.remove(looter.getUniqueId());
        if (ownerUUID == null) return;
        SleepingBody body = bodies.get(ownerUUID);
        if (body != null) body.removeLooter(looter.getUniqueId());
    }

    // ================================================
    //  PUBLIC HELPERS
    // ================================================

    public boolean isBodyEntity(int entityId) {
        return entityIdMap.containsKey(entityId);
    }

    public SleepingBody getBodyByEntityId(int entityId) {
        UUID uuid = entityIdMap.get(entityId);
        return uuid != null ? bodies.get(uuid) : null;
    }

    public SleepingBody getBody(UUID uuid) {
        return bodies.get(uuid);
    }

    public boolean hasBody(UUID uuid) {
        return bodies.containsKey(uuid);
    }

    public UUID getOwnerByLooter(Player looter) {
        return openInventories.get(looter.getUniqueId());
    }

    /** Spawn all existing bodies for a newly joined player */
    public void sendBodiesToPlayer(Player newPlayer) {
        for (SleepingBody body : bodies.values()) {
            if (!body.getOwnerUUID().equals(newPlayer.getUniqueId())) {
                npcSpawner.spawnForPlayer(body, newPlayer);
            }
        }
    }

    /** Load all bodies from DB after server restart */
    public void loadBodiesFromDatabase() {
        List<BodyData> saved = plugin.getDatabase().loadAllBodies();
        int loaded = 0;

        for (BodyData d : saved) {
            World world = Bukkit.getWorld(d.world);
            if (world == null) {
                plugin.getLogger().warning("World not found for body: " + d.world
                    + " (owner: " + d.playerName + ")");
                continue;
            }

            // Check if safezone expired while server was offline
            if (d.inSafezone && System.currentTimeMillis() >= d.safezoneExpiry) {
                // Safezone expired while offline — remove body
                plugin.getDatabase().deleteBody(d.uuid);
                plugin.getLogger().info("Removed expired safezone body for " + d.playerName);
                continue;
            }

            Location loc = new Location(world, d.x, d.y, d.z, d.yaw, 0);
            InventoryData inv = plugin.getDatabase().loadInventory(d.uuid);

            SleepingBody body = new SleepingBody(
                d.uuid, d.playerName, loc,
                d.health, d.maxHealth,
                d.inSafezone, d.safezoneExpiry,
                d.skinTexture, d.skinSignature,
                inv
            );

            bodies.put(d.uuid, body);
            npcSpawner.spawnForAll(body);
            entityIdMap.put(body.getNpcEntityId(), d.uuid);
            loaded++;
        }

        plugin.getLogger().info("Loaded " + loaded + " sleeping bodies from database.");
    }

    public void removeAllBodies() {
        if (nameTagTaskId != -1) Bukkit.getScheduler().cancelTask(nameTagTaskId);
        if (safezoneExpireTaskId != -1) Bukkit.getScheduler().cancelTask(safezoneExpireTaskId);

        for (SleepingBody body : bodies.values()) {
            npcSpawner.despawnForAll(body);
        }
        bodies.clear();
        entityIdMap.clear();
        openInventories.clear();
    }

    // ================================================
    //  DEATH SCREEN
    // ================================================

    private void handleDeath(Player player, String killerName) {
        player.getInventory().clear();
        player.teleport(player.getWorld().getSpawnLocation());

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.sendTitle(
                plugin.msg("you-died-title"),
                plugin.msg("you-died-subtitle", "{killer}", killerName != null ? killerName : "Unknown"),
                10, 120, 20
            );
            player.sendMessage(plugin.msg("you-died-chat-1"));
            player.sendMessage(plugin.msg("you-died-chat-2"));
            player.sendMessage(plugin.msg("you-died-chat-3",
                "{killer}", killerName != null ? killerName : "Unknown"));
            player.sendMessage(plugin.msg("you-died-chat-4"));
            player.sendMessage(plugin.msg("you-died-chat-5"));
            player.playSound(player.getLocation(),
                Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.0f);
        }, 20L);
    }

    // ================================================
    //  BACKGROUND TASKS
    // ================================================

    private void startNameTagTask() {
        double dist = plugin.getConfig().getDouble("settings.name-visible-distance", 8.0);
        double distSq = dist * dist;

        nameTagTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                for (SleepingBody body : bodies.values()) {
                    if (body.getNpcEntityId() == -1) continue;
                    if (!viewer.getWorld().equals(body.getLocation().getWorld())) continue;
                    if (viewer.getUniqueId().equals(body.getOwnerUUID())) continue;

                    double d = viewer.getLocation().distanceSquared(body.getLocation());
                    npcSpawner.updateNameTag(body, viewer, d <= distSq);
                }
            }
        }, 10L, 10L).getTaskId();
    }

    private void startSafezoneExpireTask() {
        // Check every 30 seconds for expired safezone bodies
        safezoneExpireTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (SleepingBody body : new ArrayList<>(bodies.values())) {
                if (body.isInSafezone() && now >= body.getSafezoneExpiry()) {
                    // Remove body when safezone protection expires
                    plugin.getLogger().info("Safezone protection expired for "
                        + body.getOwnerName() + " - removing body");
                    npcSpawner.despawnForAll(body);
                    plugin.getDatabase().deleteBody(body.getOwnerUUID());
                    entityIdMap.remove(body.getNpcEntityId());
                    bodies.remove(body.getOwnerUUID());
                    closeAllLooters(body, false);
                }
            }
        }, 600L, 600L).getTaskId();
    }

    // ================================================
    //  PRIVATE UTILS
    // ================================================

    private void saveBodyToDatabase(SleepingBody body) {
        BodyData data = new BodyData();
        data.uuid           = body.getOwnerUUID();
        data.playerName     = body.getOwnerName();
        data.world          = body.getLocation().getWorld().getName();
        data.x              = body.getLocation().getX();
        data.y              = body.getLocation().getY();
        data.z              = body.getLocation().getZ();
        data.yaw            = body.getLocation().getYaw();
        data.health         = body.getHealth();
        data.maxHealth      = body.getMaxHealth();
        data.inSafezone     = body.isInSafezone();
        data.safezoneExpiry = body.getSafezoneExpiry();
        data.skinTexture    = body.getSkinTexture();
        data.skinSignature  = body.getSkinSignature();
        data.killed         = false;
        data.killerName     = null;
        data.disconnectTime = System.currentTimeMillis();

        plugin.getDatabase().saveBody(data);
        plugin.getDatabase().saveInventory(
            body.getOwnerUUID(),
            body.getContents(),
            body.getHelmet(),
            body.getChestplate(),
            body.getLeggings(),
            body.getBoots(),
            body.getOffhand()
        );
    }

    private void closeAllLooters(SleepingBody body, boolean killed) {
        for (UUID looterUUID : new HashSet<>(body.getActiveLooters())) {
            Player looter = Bukkit.getPlayer(looterUUID);
            if (looter != null) {
                looter.closeInventory();
                if (!killed)
                    looter.sendMessage(plugin.prefix() + plugin.msg("inventory-closed"));
            }
            openInventories.remove(looterUUID);
        }
        body.getActiveLooters().clear();
    }

    private int countItems(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) count++;
        }
        return count;
    }

    private String formatName(String name) {
        String[] words = name.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words)
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        return sb.toString().trim();
    }
}
