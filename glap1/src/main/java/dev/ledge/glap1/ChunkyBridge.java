package dev.ledge.glap1;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.popcraft.chunky.api.ChunkyAPI;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional Chunky public-API adapter. Chunky remains a separate server plugin. */
final class ChunkyBridge {
    private final JavaPlugin plugin;
    private final ChunkyAPI api;
    private final Map<String, AtomicInteger> lastProgress = new ConcurrentHashMap<>();

    private ChunkyBridge(JavaPlugin plugin, ChunkyAPI api) {
        this.plugin = plugin;
        this.api = api;
        api.onGenerationProgress(event -> {
            int progress = Math.max(0, Math.min(100, Math.round(event.progress())));
            AtomicInteger previous = lastProgress.computeIfAbsent(event.world(), ignored -> new AtomicInteger(-10));
            int before = previous.get();
            if (progress == 100 || progress >= before + 10 && previous.compareAndSet(before, progress)) {
                plugin.getLogger().info("Chunky pre-generation " + event.world() + ": " + progress
                        + "% (" + event.chunks() + " chunks)");
            }
        });
        api.onGenerationComplete(event -> plugin.getLogger().info("Chunky pre-generation complete: " + event.world()));
    }

    static ChunkyBridge connect(JavaPlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("Chunky") == null) {
            plugin.getLogger().info("Chunky not found; map viewing remains available without pre-generation.");
            return null;
        }
        ChunkyAPI api = Bukkit.getServicesManager().load(ChunkyAPI.class);
        if (api == null || api.version() != 0) {
            plugin.getLogger().warning("Chunky API unavailable or unsupported; pre-generation integration disabled.");
            return null;
        }
        plugin.getLogger().info("Connected to Chunky API v" + api.version() + ".");
        return new ChunkyBridge(plugin, api);
    }

    String start(String worldName, double radius) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return "Unknown world: " + worldName;
        if (radius < 64 || radius > 30_000) return "Radius must be between 64 and 30000 blocks.";
        if (api.isRunning(worldName)) return "Chunky is already generating this world.";
        boolean started = api.startTask(worldName, "square", world.getSpawnLocation().getX(),
                world.getSpawnLocation().getZ(), radius, radius, "concentric");
        return started ? "Started Chunky pre-generation for " + worldName + "." : "Chunky could not start the task.";
    }

    String pause(String world) {
        return api.pauseTask(world) ? "Paused Chunky for " + world + "." : "No Chunky task to pause for " + world + ".";
    }

    String resume(String world) {
        return api.continueTask(world) ? "Resumed Chunky for " + world + "." : "No saved Chunky task for " + world + ".";
    }

    String cancel(String world) {
        return api.cancelTask(world) ? "Cancelled Chunky for " + world + "." : "No Chunky task to cancel for " + world + ".";
    }
}
