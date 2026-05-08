package com.fz.rustsleep.database;

import com.fz.rustsleep.RustSleep;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class DatabaseManager {

    private final RustSleep plugin;
    private Connection connection;

    public DatabaseManager(RustSleep plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            plugin.getDataFolder().mkdirs();
            String path = plugin.getDataFolder().getAbsolutePath() + "/rustsleep.db";
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            createTables();
            plugin.getLogger().info("Database initialized.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to init database!", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Main body table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sleeping_bodies (
                    uuid            TEXT PRIMARY KEY,
                    player_name     TEXT NOT NULL,
                    world           TEXT NOT NULL,
                    x               REAL NOT NULL,
                    y               REAL NOT NULL,
                    z               REAL NOT NULL,
                    yaw             REAL NOT NULL,
                    health          REAL NOT NULL,
                    max_health      REAL NOT NULL,
                    in_safezone     INTEGER NOT NULL DEFAULT 0,
                    safezone_expiry BIGINT NOT NULL DEFAULT 0,
                    skin_texture    TEXT,
                    skin_signature  TEXT,
                    killed          INTEGER NOT NULL DEFAULT 0,
                    killer_name     TEXT,
                    disconnect_time BIGINT NOT NULL
                )
            """);

            // Inventory storage (serialized)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS body_inventory (
                    uuid        TEXT NOT NULL,
                    slot        INTEGER NOT NULL,
                    item_data   BLOB NOT NULL,
                    PRIMARY KEY (uuid, slot)
                )
            """);

            // Player playtime tracking
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_playtime (
                    uuid          TEXT PRIMARY KEY,
                    player_name   TEXT NOT NULL,
                    total_minutes REAL NOT NULL DEFAULT 0,
                    last_join     BIGINT NOT NULL DEFAULT 0
                )
            """);
        }
    }

    // ============================
    //  BODY PERSISTENCE
    // ============================

    public void saveBody(BodyData data) {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR REPLACE INTO sleeping_bodies
            (uuid, player_name, world, x, y, z, yaw, health, max_health,
             in_safezone, safezone_expiry, skin_texture, skin_signature,
             killed, killer_name, disconnect_time)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """)) {
            ps.setString(1, data.uuid.toString());
            ps.setString(2, data.playerName);
            ps.setString(3, data.world);
            ps.setDouble(4, data.x);
            ps.setDouble(5, data.y);
            ps.setDouble(6, data.z);
            ps.setFloat(7, data.yaw);
            ps.setDouble(8, data.health);
            ps.setDouble(9, data.maxHealth);
            ps.setInt(10, data.inSafezone ? 1 : 0);
            ps.setLong(11, data.safezoneExpiry);
            ps.setString(12, data.skinTexture);
            ps.setString(13, data.skinSignature);
            ps.setInt(14, data.killed ? 1 : 0);
            ps.setString(15, data.killerName);
            ps.setLong(16, data.disconnectTime);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save body: " + data.uuid, e);
        }
    }

    public void saveInventory(UUID uuid, ItemStack[] contents,
                               ItemStack helmet, ItemStack chestplate,
                               ItemStack leggings, ItemStack boots, ItemStack offhand) {
        ensureConnection();
        // Delete existing
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM body_inventory WHERE uuid = ?")) {
            del.setString(1, uuid.toString());
            del.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to clear inventory: " + uuid, e);
        }

        // Save each slot
        Map<Integer, ItemStack> slots = new HashMap<>();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) slots.put(i, contents[i]);
        }
        // Armor in special slots 100-103, offhand 104
        if (helmet     != null) slots.put(100, helmet);
        if (chestplate != null) slots.put(101, chestplate);
        if (leggings   != null) slots.put(102, leggings);
        if (boots      != null) slots.put(103, boots);
        if (offhand    != null) slots.put(104, offhand);

        for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO body_inventory (uuid, slot, item_data) VALUES (?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, entry.getKey());
                ps.setBytes(3, serializeItem(entry.getValue()));
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save item slot " + entry.getKey(), e);
            }
        }
    }

    public InventoryData loadInventory(UUID uuid) {
        ensureConnection();
        InventoryData data = new InventoryData();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT slot, item_data FROM body_inventory WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int slot = rs.getInt("slot");
                ItemStack item = deserializeItem(rs.getBytes("item_data"));
                if (item == null) continue;
                if (slot == 100) data.helmet     = item;
                else if (slot == 101) data.chestplate = item;
                else if (slot == 102) data.leggings   = item;
                else if (slot == 103) data.boots       = item;
                else if (slot == 104) data.offhand     = item;
                else if (slot >= 0 && slot < 36) data.contents[slot] = item;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load inventory: " + uuid, e);
        }
        return data;
    }

    public List<BodyData> loadAllBodies() {
        ensureConnection();
        List<BodyData> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM sleeping_bodies WHERE killed = 0")) {
            while (rs.next()) {
                BodyData d = new BodyData();
                d.uuid           = UUID.fromString(rs.getString("uuid"));
                d.playerName     = rs.getString("player_name");
                d.world          = rs.getString("world");
                d.x              = rs.getDouble("x");
                d.y              = rs.getDouble("y");
                d.z              = rs.getDouble("z");
                d.yaw            = rs.getFloat("yaw");
                d.health         = rs.getDouble("health");
                d.maxHealth      = rs.getDouble("max_health");
                d.inSafezone     = rs.getInt("in_safezone") == 1;
                d.safezoneExpiry = rs.getLong("safezone_expiry");
                d.skinTexture    = rs.getString("skin_texture");
                d.skinSignature  = rs.getString("skin_signature");
                d.killed         = rs.getInt("killed") == 1;
                d.killerName     = rs.getString("killer_name");
                d.disconnectTime = rs.getLong("disconnect_time");
                list.add(d);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load bodies!", e);
        }
        return list;
    }

    public void deleteBody(UUID uuid) {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM sleeping_bodies WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete body: " + uuid, e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM body_inventory WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete inventory: " + uuid, e);
        }
    }

    public void markKilled(UUID uuid, String killerName) {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sleeping_bodies SET killed = 1, killer_name = ? WHERE uuid = ?")) {
            ps.setString(1, killerName);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to mark killed: " + uuid, e);
        }
    }

    public void updateHealth(UUID uuid, double health) {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sleeping_bodies SET health = ? WHERE uuid = ?")) {
            ps.setDouble(1, health);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update health: " + uuid, e);
        }
    }

    public void deleteInventorySlot(UUID uuid, int slot) {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM body_inventory WHERE uuid = ? AND slot = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete slot: " + slot, e);
        }
    }

    // ============================
    //  PLAYTIME TRACKING
    // ============================

    public double getPlaytimeMinutes(UUID uuid) {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT total_minutes FROM player_playtime WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getDouble("total_minutes");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get playtime: " + uuid, e);
        }
        return 0;
    }

    public void updatePlaytime(UUID uuid, String name, double minutes) {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO player_playtime (uuid, player_name, total_minutes, last_join)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                total_minutes = total_minutes + ?,
                player_name = ?,
                last_join = ?
        """)) {
            long now = System.currentTimeMillis();
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setDouble(3, minutes);
            ps.setLong(4, now);
            ps.setDouble(5, minutes);
            ps.setString(6, name);
            ps.setLong(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update playtime: " + uuid, e);
        }
    }

    // ============================
    //  HELPERS
    // ============================

    private void ensureConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                String path = plugin.getDataFolder().getAbsolutePath() + "/rustsleep.db";
                connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "DB reconnect failed!", e);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }

    private byte[] serializeItem(ItemStack item) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeObject(item);
            return baos.toByteArray();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to serialize item: " + e.getMessage());
            return new byte[0];
        }
    }

    private ItemStack deserializeItem(byte[] data) {
        if (data == null || data.length == 0) return null;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            return (ItemStack) ois.readObject();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to deserialize item: " + e.getMessage());
            return null;
        }
    }

    // ============================
    //  DATA CLASSES
    // ============================

    public static class BodyData {
        public UUID uuid;
        public String playerName;
        public String world;
        public double x, y, z;
        public float yaw;
        public double health, maxHealth;
        public boolean inSafezone;
        public long safezoneExpiry;
        public String skinTexture, skinSignature;
        public boolean killed;
        public String killerName;
        public long disconnectTime;
    }

    public static class InventoryData {
        public ItemStack[] contents = new ItemStack[36];
        public ItemStack helmet, chestplate, leggings, boots, offhand;
    }
}
