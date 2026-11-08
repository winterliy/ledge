package dev.ledge.glap1;

import dev.ledge.common.MapTileKey;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Folia-style regionized workers for the off-thread part of the map pipeline (sampling snapshots, composing,
 * projecting, PNG encoding and cache I/O). Tiles are grouped into regions of {@value #REGION_TILES}x{@value #REGION_TILES}
 * tiles; a region always goes to the same worker, so neighboring tiles share warm caches and never contend, while
 * different regions are processed in parallel.
 */
final class RegionWorkers {
    static final int REGION_TILES = 8;

    private final ThreadPoolExecutor[] workers;

    RegionWorkers(int count, int queuePerWorker) {
        workers = new ThreadPoolExecutor[Math.max(1, count)];
        for (int i = 0; i < workers.length; i++) {
            int index = i;
            workers[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(Math.max(16, queuePerWorker)), runnable -> {
                Thread thread = new Thread(runnable, "Ledge-Region-Worker-" + index);
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    int size() {
        return workers.length;
    }

    /** Runs the task on the worker that owns the tile's region; throws RejectedExecutionException when full. */
    void execute(MapTileKey key, Runnable task) {
        int regionX = Math.floorDiv(key.tileX(), REGION_TILES);
        int regionZ = Math.floorDiv(key.tileZ(), REGION_TILES);
        int hash = 31 * (31 * key.worldId().hashCode() + regionX) + regionZ + key.zoom() * 7919;
        workers[Math.floorMod(hash, workers.length)].execute(task);
    }

    void shutdown() {
        for (ThreadPoolExecutor worker : workers) worker.shutdown();
        try {
            for (ThreadPoolExecutor worker : workers) {
                if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            for (ThreadPoolExecutor worker : workers) worker.shutdownNow();
        }
    }
}
