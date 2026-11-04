package dev.ledge.glap1;

import dev.ledge.common.FileMapTileStore;
import dev.ledge.common.MapBlockSample;
import dev.ledge.common.MapRendererType;
import dev.ledge.common.MapResolution;
import dev.ledge.common.MapTileKey;
import dev.ledge.common.MapTileRenderers;
import dev.ledge.common.MapWorldConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;

/** Samples loaded world columns on the server thread and composes tiles off-thread. */
final class MapTileEngine extends BukkitRunnable {
    private final JavaPlugin plugin;
    private final FileMapTileStore store;
    private final int maxSamplesPerTick;
    private final ConcurrentLinkedQueue<Job> pending = new ConcurrentLinkedQueue<>();
    private final Map<MapTileKey, Job> queued = new ConcurrentHashMap<>();
    private final Set<MapTileKey> rendering = ConcurrentHashMap.newKeySet();
    private final Set<MapTileKey> dirty = ConcurrentHashMap.newKeySet();
    private final Map<MapTileKey, AtomicLong> revisions = new ConcurrentHashMap<>();
    private final Map<MapTileKey, java.util.concurrent.CopyOnWriteArraySet<java.util.function.Consumer<byte[]>>> readyCallbacks = new ConcurrentHashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.BiConsumer<MapTileKey, byte[]>> tileListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private final ThreadPoolExecutor renderer = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), runnable -> {
        Thread thread = new Thread(runnable, "Ledge-Tile-Renderer");
        thread.setDaemon(true);
        return thread;
    });

    MapTileEngine(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new FileMapTileStore(plugin.getDataFolder().toPath().resolve("tiles"));
        this.maxSamplesPerTick = Math.max(256, plugin.getConfig().getInt("render.max-samples-per-tick", 2048));
    }

    FileMapTileStore store() {
        return store;
    }

    Optional<BufferedImage> read(MapTileKey key) throws IOException {
        if (dirty.contains(key)) {
            request(key);
            return Optional.empty();
        }
        Optional<BufferedImage> image = store.read(key);
        if (image.isEmpty()) {
            request(key);
        }
        return image;
    }

    void readOrRequest(MapTileKey key, java.util.function.Consumer<byte[]> callback) {
        if (closed) return;
        readyCallbacks.computeIfAbsent(key, ignored -> new java.util.concurrent.CopyOnWriteArraySet<>()).add(callback);
        try {
            renderer.execute(() -> {
                try {
                    Optional<BufferedImage> image = dirty.contains(key) ? Optional.empty() : store.read(key);
                    if (image.isPresent()) {
                        byte[] png = encodePng(image.get());
                        Bukkit.getScheduler().runTask(plugin, () -> deliver(key, png));
                    } else {
                        request(key);
                    }
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not read map tile " + key, exception);
                    Bukkit.getScheduler().runTask(plugin, () -> request(key));
                }
            });
        } catch (RejectedExecutionException exception) {
            readyCallbacks.remove(key);
            plugin.getLogger().warning("Map render queue is full; dropping tile read " + key);
        }
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private void deliver(MapTileKey key, byte[] png) {
        var callbacks = readyCallbacks.remove(key);
        if (callbacks != null) callbacks.forEach(callback -> callback.accept(png));
    }

    void addTileListener(java.util.function.BiConsumer<MapTileKey, byte[]> listener) {
        tileListeners.add(listener);
    }

    void removeTileListener(java.util.function.BiConsumer<MapTileKey, byte[]> listener) {
        tileListeners.remove(listener);
    }

    void request(MapTileKey key) {
        if (closed) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> request(key));
            return;
        }
        if (queued.containsKey(key)) {
            return;
        }
        World world = Bukkit.getWorld(worldName(key.worldId()));
        if (world == null || !dimensionId(world).equals(key.dimensionId())) {
            return;
        }
        dirty.add(key);
        Job job = new Job(key, world, revision(key));
        if (queued.putIfAbsent(key, job) == null) {
            pending.add(job);
        }
    }

    void invalidate(World world, int blockX, int blockZ) {
        MapWorldConfig config = configFor(world);
        MapTileKey key = MapTileKey.atBlock(
                worldId(world), dimensionId(world), config.renderer(), config.resolution(), 0, blockX, blockZ
        );
        dirty.add(key);
        revisions.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        if (queued.containsKey(key)) {
            return;
        }
        Job job = new Job(key, world, revision(key));
        if (queued.putIfAbsent(key, job) == null) {
            pending.add(job);
        }
    }

    @Override
    public void run() {
        int remaining = maxSamplesPerTick;
        while (remaining > 0) {
            Job job = pending.peek();
            if (job == null) {
                return;
            }
            int processed = job.sample(remaining);
            remaining -= Math.max(processed, 1);
            if (job.complete()) {
                pending.poll();
                renderAsync(job);
            } else if (processed == 0) {
                return;
            }
        }
    }

    void shutdown() {
        closed = true;
        cancel();
        renderer.shutdown();
        try {
            if (!renderer.awaitTermination(2, TimeUnit.SECONDS)) {
                renderer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            renderer.shutdownNow();
        }
    }

    static String dimensionId(World world) {
        return world.getKey().toString().replace(':', '_');
    }

    static String worldId(World world) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(world.getName().getBytes(StandardCharsets.UTF_8));
    }

    private static String worldName(String worldId) {
        try {
            return new String(Base64.getUrlDecoder().decode(worldId), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    static MapWorldConfig configFor(World world) {
        String path = "worlds." + world.getName() + "." + dimensionId(world);
        String rendererName = Bukkit.getPluginManager().getPlugin("Ledge")
                .getConfig().getString(path + ".renderer", "SURFACE");
        String resolutionName = Bukkit.getPluginManager().getPlugin("Ledge")
                .getConfig().getString(path + ".resolution", "STANDARD");
        boolean liveUpdates = Bukkit.getPluginManager().getPlugin("Ledge")
                .getConfig().getBoolean(path + ".live-updates", true);
        return new MapWorldConfig(worldId(world), dimensionId(world), parseRenderer(rendererName),
                parseResolution(resolutionName), liveUpdates);
    }

    static MapRendererType parseRenderer(String name) {
        try {
            return MapRendererType.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ignored) {
            return MapRendererType.SURFACE;
        }
    }

    static MapResolution parseResolution(String name) {
        try {
            return MapResolution.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ignored) {
            return MapResolution.STANDARD;
        }
    }

    private void renderAsync(Job job) {
        if (!rendering.add(job.key)) return;
        try {
            renderer.execute(() -> {
                try {
                    BufferedImage image = MapTileRenderers.forType(job.key.renderer())
                            .render(job.samples, job.key.resolution());
                    store.write(job.key, image);
                    if (revision(job.key) == job.revision) {
                        dirty.remove(job.key);
                        if (readyCallbacks.containsKey(job.key) || !tileListeners.isEmpty()) {
                            byte[] png = encodePng(image);
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                deliver(job.key, png);
                                tileListeners.forEach(listener -> listener.accept(job.key, png));
                            });
                        }
                    } else {
                        request(job.key);
                    }
                } catch (IOException | RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not render map tile " + job.key, exception);
                } finally {
                    rendering.remove(job.key);
                    queued.remove(job.key, job);
                    if (dirty.contains(job.key) && revision(job.key) != job.revision) {
                        Bukkit.getScheduler().runTask(plugin, () -> request(job.key));
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            rendering.remove(job.key);
            queued.remove(job.key, job);
            plugin.getLogger().warning("Map render queue is full; dropping tile " + job.key);
            dirty.add(job.key);
        }
    }

    private long revision(MapTileKey key) {
        return revisions.computeIfAbsent(key, ignored -> new AtomicLong()).get();
    }

    private static int colorFor(Material material) {
        String name = material.name();
        if (name.contains("WATER") || name.contains("ICE")) return 0xff4f9ec4;
        if (name.contains("LAVA") || name.contains("MAGMA")) return 0xffd65a27;
        if (name.contains("LEAVES") || name.contains("MOSS") || name.contains("GRASS") || name.contains("VINE")) return 0xff5e9b4f;
        if (name.contains("FLOWER") || name.contains("TULIP")) return 0xff8ca85a;
        if (name.contains("SAND") || name.contains("SANDSTONE") || name.contains("BEACH")) return 0xffd8c27c;
        if (name.contains("SNOW") || name.contains("QUARTZ")) return 0xffe6e8e8;
        if (name.contains("WOOD") || name.contains("LOG") || name.contains("PLANK") || name.contains("BARK")) return 0xff92714d;
        if (name.contains("DIRT") || name.contains("ROOTED") || name.contains("PODZOL")) return 0xff866448;
        if (name.contains("NETHERRACK") || name.contains("CRIMSON") || name.contains("NETHER")) return 0xff8d4242;
        if (name.contains("END_STONE")) return 0xffd5ce8c;
        if (name.contains("STONE") || name.contains("ORE") || name.contains("DEEPSLATE") || name.contains("COBBLE")) return 0xff888b8e;
        if (!material.isSolid()) return 0x00000000;
        return 0xffa08c72;
    }

    private final class Job {
        private final MapTileKey key;
        private final World world;
        private final MapBlockSample[] samples;
        private final int side;
        private final long revision;
        private int index;

        private Job(MapTileKey key, World world, long revision) {
            this.key = key;
            this.world = world;
            this.revision = revision;
            this.side = key.samplesPerSide();
            this.samples = new MapBlockSample[side * side];
        }

        private int sample(int budget) {
            int count = 0;
            int spacing = key.blocksPerSample();
            while (index < samples.length && count < budget) {
                int sampleX = index % side;
                int sampleZ = index / side;
                int x = key.minBlockX() + sampleX * spacing;
                int z = key.minBlockZ() + sampleZ * spacing;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    samples[index] = new MapBlockSample(0x00000000, world.getMinHeight(), false);
                } else if (key.renderer() == MapRendererType.CAVE) {
                    Block surface = world.getHighestBlockAt(x, z);
                    int caveY = caveSliceY(world);
                    Block floor = world.getBlockAt(x, caveY, z);
                    Block above = world.getBlockAt(x, caveY + 1, z);
                    boolean underground = surface.getY() > caveY + 2;
                    boolean exposed = underground && floor.getType().isSolid() && !above.getType().isSolid();
                    samples[index] = new MapBlockSample(colorFor(floor.getType()), caveY, exposed);
                } else {
                    Block top = world.getHighestBlockAt(x, z);
                    samples[index] = new MapBlockSample(colorFor(top.getType()), top.getY(), false);
                }
                index++;
                count++;
            }
            return count;
        }

        private boolean complete() {
            return index >= samples.length;
        }
    }

    private static int caveSliceY(World world) {
        int min = world.getMinHeight();
        int max = world.getMaxHeight() - 1;
        return Math.max(min + 1, Math.min(max - 1, min + (max - min) / 4));
    }
}
