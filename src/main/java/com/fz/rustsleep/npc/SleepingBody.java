package com.fz.rustsleep.npc;

import com.fz.rustsleep.database.DatabaseManager.InventoryData;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SleepingBody {

    // Identity
    private final UUID ownerUUID;
    private final String ownerName;
    private final Location location;

    // Stats
    private double health;
    private double maxHealth;

    // Inventory
    private ItemStack[] contents;
    private ItemStack helmet, chestplate, leggings, boots, offhand;

    // NPC
    private int npcEntityId = -1;
    private final UUID npcUUID = UUID.randomUUID();

    // Skin
    private String skinTexture;
    private String skinSignature;

    // Safe zone
    private boolean inSafezone;
    private long safezoneExpiry; // epoch millis

    // Death tracking
    private boolean killed = false;
    private String killerName = null;

    // Active looters
    private final Set<UUID> activeLooters = new HashSet<>();

    // ---- Constructor from live player ----
    public SleepingBody(org.bukkit.entity.Player player) {
        this.ownerUUID  = player.getUniqueId();
        this.ownerName  = player.getName();
        this.location   = player.getLocation().clone();
        this.health     = player.getHealth();
        this.maxHealth  = player.getAttribute(
            org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();

        this.contents    = cloneArray(player.getInventory().getContents());
        this.helmet      = clone(player.getInventory().getHelmet());
        this.chestplate  = clone(player.getInventory().getChestplate());
        this.leggings    = clone(player.getInventory().getLeggings());
        this.boots       = clone(player.getInventory().getBoots());
        this.offhand     = player.getInventory().getItemInOffHand().getType()
                           != org.bukkit.Material.AIR
                           ? player.getInventory().getItemInOffHand().clone() : null;
    }

    // ---- Constructor from database ----
    public SleepingBody(UUID uuid, String name, Location location,
                        double health, double maxHealth,
                        boolean inSafezone, long safezoneExpiry,
                        String skinTexture, String skinSignature,
                        InventoryData invData) {
        this.ownerUUID       = uuid;
        this.ownerName       = name;
        this.location        = location;
        this.health          = health;
        this.maxHealth       = maxHealth;
        this.inSafezone      = inSafezone;
        this.safezoneExpiry  = safezoneExpiry;
        this.skinTexture     = skinTexture;
        this.skinSignature   = skinSignature;

        if (invData != null) {
            this.contents    = invData.contents;
            this.helmet      = invData.helmet;
            this.chestplate  = invData.chestplate;
            this.leggings    = invData.leggings;
            this.boots       = invData.boots;
            this.offhand     = invData.offhand;
        } else {
            this.contents = new ItemStack[36];
        }
    }

    // ---- Safe zone helpers ----

    public boolean isInSafezone()    { return inSafezone; }
    public void setInSafezone(boolean b) { inSafezone = b; }
    public long getSafezoneExpiry()  { return safezoneExpiry; }
    public void setSafezoneExpiry(long t) { safezoneExpiry = t; }

    public boolean isSafezoneProtected() {
        return inSafezone && System.currentTimeMillis() < safezoneExpiry;
    }

    public long getRemainingProtectionMinutes() {
        if (!isSafezoneProtected()) return 0;
        return (safezoneExpiry - System.currentTimeMillis()) / 60000L;
    }

    // ---- Inventory ----

    public ItemStack takeItem(int slot) {
        if (slot < 0 || slot >= contents.length) return null;
        ItemStack item = contents[slot];
        contents[slot] = null;
        return item;
    }

    public void dropAllItems() {
        org.bukkit.World world = location.getWorld();
        if (world == null) return;
        for (ItemStack i : contents)
            if (i != null && i.getType() != org.bukkit.Material.AIR)
                world.dropItemNaturally(location, i);
        if (helmet     != null) world.dropItemNaturally(location, helmet);
        if (chestplate != null) world.dropItemNaturally(location, chestplate);
        if (leggings   != null) world.dropItemNaturally(location, leggings);
        if (boots      != null) world.dropItemNaturally(location, boots);
        if (offhand    != null) world.dropItemNaturally(location, offhand);
        contents    = new ItemStack[36];
        helmet = chestplate = leggings = boots = offhand = null;
    }

    public void restoreInventory(org.bukkit.entity.Player player) {
        player.getInventory().clear();
        player.getInventory().setContents(contents);
        if (helmet     != null) player.getInventory().setHelmet(helmet);
        if (chestplate != null) player.getInventory().setChestplate(chestplate);
        if (leggings   != null) player.getInventory().setLeggings(leggings);
        if (boots      != null) player.getInventory().setBoots(boots);
        if (offhand    != null) player.getInventory().setItemInOffHand(offhand);
    }

    // ---- Looters ----

    public void addLooter(UUID uuid)    { activeLooters.add(uuid); }
    public void removeLooter(UUID uuid) { activeLooters.remove(uuid); }
    public Set<UUID> getActiveLooters() { return activeLooters; }

    // ---- Getters / Setters ----

    public UUID getOwnerUUID()       { return ownerUUID; }
    public String getOwnerName()     { return ownerName; }
    public Location getLocation()    { return location.clone(); }
    public double getHealth()        { return health; }
    public double getMaxHealth()     { return maxHealth; }
    public int getNpcEntityId()      { return npcEntityId; }
    public UUID getNpcUUID()         { return npcUUID; }
    public String getSkinTexture()   { return skinTexture; }
    public String getSkinSignature() { return skinSignature; }
    public boolean wasKilled()       { return killed; }
    public String getKillerName()    { return killerName; }
    public ItemStack[] getContents() { return contents; }
    public ItemStack getHelmet()     { return helmet; }
    public ItemStack getChestplate() { return chestplate; }
    public ItemStack getLeggings()   { return leggings; }
    public ItemStack getBoots()      { return boots; }
    public ItemStack getOffhand()    { return offhand; }

    public void setNpcEntityId(int id)       { npcEntityId = id; }
    public void setSkinTexture(String t)     { skinTexture = t; }
    public void setSkinSignature(String s)   { skinSignature = s; }
    public void setHealth(double h)          { health = Math.max(0, h); }
    public void setKilled(boolean b)         { killed = b; }
    public void setKillerName(String name)   { killerName = name; }

    // ---- Utils ----

    private ItemStack[] cloneArray(ItemStack[] arr) {
        ItemStack[] c = new ItemStack[arr.length];
        for (int i = 0; i < arr.length; i++)
            c[i] = arr[i] != null ? arr[i].clone() : null;
        return c;
    }

    private ItemStack clone(ItemStack item) {
        return item != null ? item.clone() : null;
    }
}
