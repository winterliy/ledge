package dev.ledge.glap1;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Region-aware scheduling that works unchanged on Paper and Folia. On Paper the global and region schedulers run
 * on the main thread; on Folia they run on the global/region threads that own the data.
 */
final class Schedulers {
    private Schedulers() { }

    static void global(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    static ScheduledTask globalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.run(), Math.max(1, delayTicks), periodTicks);
    }

    static void region(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    static ScheduledTask asyncTimer(Plugin plugin, Runnable task, long delayMillis, long periodMillis) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> task.run(), delayMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /** Average tick time in ms, or 0 when the server cannot report it (e.g. Folia's regionized ticking). */
    static double averageTickMillis() {
        try {
            return Bukkit.getServer().getAverageTickTime();
        } catch (UnsupportedOperationException | NoSuchMethodError exception) {
            return 0;
        }
    }
}
