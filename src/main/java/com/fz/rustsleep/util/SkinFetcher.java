package com.fz.rustsleep.util;

import com.fz.rustsleep.RustSleep;
import com.fz.rustsleep.npc.SleepingBody;
import net.skinsrestorer.api.SkinsRestorerProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class SkinFetcher {

    private final RustSleep plugin;
    private final boolean srAvailable;

    public SkinFetcher(RustSleep plugin) {

        this.plugin = plugin;

        Plugin sr = Bukkit.getPluginManager().getPlugin("SkinsRestorer");

        srAvailable = sr != null && sr.isEnabled();

        plugin.getLogger().info(
            "SkinsRestorer: " + (srAvailable ? "found" : "not found")
        );
    }

    /**
     * Fetch skin async and apply to body
     */
    public void fetchSkin(
        SleepingBody body,
        Player player,
        Runnable onDone
    ) {

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {

            String texture = null;
            String signature = null;

            // Try SkinsRestorer first
            if (srAvailable) {

                try {

                    var srAPI = SkinsRestorerProvider.get();

                    var playerStorage = srAPI.getPlayerStorage();

                    var skinId =
                        playerStorage.getSkinIdOfPlayer(
                            player.getUniqueId()
                        );

                    if (skinId.isPresent()) {

                        var skinData =
                            srAPI.getSkinStorage().getPlayerSkin(
                                skinId.get().toString(),
                                false
                            );

                        if (skinData.isPresent()) {

                            texture = skinData.get().getValue();

                            signature = skinData.get().getSignature();
                        }
                    }

                } catch (Exception e) {

                    plugin.getLogger().warning(
                        "SkinsRestorer fetch failed for "
                            + player.getName()
                            + ": "
                            + e.getMessage()
                    );
                }
            }

            // Fallback from Bukkit profile
            if (texture == null) {

                try {

                    for (var prop :
                        player.getPlayerProfile().getProperties()) {

                        if (prop.getName().equals("textures")) {

                            texture = prop.getValue();

                            signature = prop.getSignature();

                            break;
                        }
                    }

                } catch (Exception ignored) {
                }
            }

            body.setSkinTexture(texture);

            body.setSkinSignature(signature);

            Bukkit.getScheduler().runTask(plugin, onDone);
        });
    }
}
