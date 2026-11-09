package dev.ledge.glap1;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Scheduling on servers older than Paper 1.20 (no global/region/async schedulers): everything maps onto the Bukkit
 * scheduler, whose "global" and "region" work runs on the single main thread.
 */
final class Schedulers {
    private Schedulers() { }

    static void global(Plugin plugin, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    static TaskHandle globalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        BukkitTask handle = Bukkit.getScheduler().runTaskTimer(plugin, task, Math.max(1, delayTicks), periodTicks);
        return handle::cancel;
    }

    static void region(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    static TaskHandle asyncTimer(Plugin plugin, Runnable task, long delayMillis, long periodMillis) {
        BukkitTask handle = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, Math.max(1L, delayMillis / 50L), Math.max(1L, periodMillis / 50L));
        return handle::cancel;
    }

    /** Average tick time in ms, or 0 when the server cannot report it. */
    static double averageTickMillis() {
        try {
            return Bukkit.getServer().getAverageTickTime();
        } catch (UnsupportedOperationException | NoSuchMethodError exception) {
            return 0;
        }
    }
}
