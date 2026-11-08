package dev.ledge.glap1;

import dev.ledge.common.FileMapTileStore;
import dev.ledge.common.MapBlockSample;
import dev.ledge.common.MapHeightTile;
import dev.ledge.common.MapProjectedTile;
import dev.ledge.common.MapRendererType;
import dev.ledge.common.MapResolution;
import dev.ledge.common.MapTileKey;
import dev.ledge.common.MapTileRenderers;
import dev.ledge.common.MapTileProjector;
import dev.ledge.common.MapWorldConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

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
final class MapTileEngine implements Runnable {
    private final JavaPlugin plugin;
    private final FileMapTileStore store;
    private final int maxSamplesPerTick;
    private final boolean diskLoading;
    private final int maxSnapshotsInFlight;
    private final java.util.concurrent.atomic.AtomicInteger snapshotsInFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final ConcurrentLinkedQueue<Job> pending = new ConcurrentLinkedQueue<>();
    private final Map<MapTileKey, Job> queued = new ConcurrentHashMap<>();
    private final Set<MapTileKey> rendering = ConcurrentHashMap.newKeySet();
    private final Set<MapTileKey> dirty = ConcurrentHashMap.newKeySet();
    private final Set<MapTileKey> subscribedTiles = ConcurrentHashMap.newKeySet();
    private final Map<MapTileKey, AtomicLong> revisions = new ConcurrentHashMap<>();
    private final Map<WorldDimension, AtomicLong> worldRevisions = new ConcurrentHashMap<>();
    private final Map<WorldDimension, AtomicLong> coarseRevisions = new ConcurrentHashMap<>();
    private final Map<WorldDimension, Map<Long, Long>> changedChunks = new ConcurrentHashMap<>();
    private final Map<MapTileKey, Long> tileRevisions = new ConcurrentHashMap<>();
    private final Map<MapTileKey, java.util.concurrent.CopyOnWriteArraySet<java.util.function.Consumer<byte[]>>> readyCallbacks = new ConcurrentHashMap<>();
    private final Map<MapTileKey, java.util.concurrent.CopyOnWriteArraySet<java.util.function.Consumer<MapProjectedTile>>> projectedCallbacks = new ConcurrentHashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.BiConsumer<MapTileKey, byte[]>> tileListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private final RegionWorkers renderer;
    /** Each region worker keeps its own region-file reader (Folia-style: no shared state between regions). */
    private final ThreadLocal<AnvilRegionReader> regionReaders = ThreadLocal.withInitial(AnvilRegionReader::new);

    MapTileEngine(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new FileMapTileStore(plugin.getDataFolder().toPath().resolve("tiles"));
        int configuredWorkers = plugin.getConfig().getInt("render.workers", 0);
        int workerCount = configuredWorkers > 0 ? configuredWorkers
                : Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        this.renderer = new RegionWorkers(workerCount, 256 / workerCount + 16);
        plugin.getLogger().info("Ledge map uses " + workerCount + " region workers.");
        this.maxSamplesPerTick = Math.max(256, plugin.getConfig().getInt("render.max-samples-per-tick", 2048));
        this.diskLoading = plugin.getConfig().getBoolean("render.disk-chunks.enabled", true);
        this.maxSnapshotsInFlight = Math.max(1, plugin.getConfig().getInt("render.snapshots-in-flight", 2048));
    }

    FileMapTileStore store() {
        return store;
    }

    Optional<BufferedImage> read(MapTileKey key) throws IOException {
        Optional<BufferedImage> image = readFresh(key);
        if (image.isEmpty()) request(key);
        return image;
    }

    void readOrRequest(MapTileKey key, java.util.function.Consumer<byte[]> callback) {
        if (closed) return;
        readyCallbacks.computeIfAbsent(key, ignored -> new java.util.concurrent.CopyOnWriteArraySet<>()).add(callback);
        try {
            renderer.execute(key, () -> {
                try {
                    Optional<BufferedImage> image = readFresh(key);
                    if (image.isPresent()) {
                        byte[] png = encodePng(image.get());
                        Schedulers.global(plugin, () -> deliver(key, png));
                    } else {
                        request(key);
                    }
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not read map tile " + key, exception);
                    Schedulers.global(plugin, () -> request(key));
                }
            });
        } catch (RejectedExecutionException exception) {
            readyCallbacks.remove(key);
            plugin.getLogger().warning("Map render queue is full; dropping tile read " + key);
        }
    }

    Optional<MapProjectedTile> readProjected(MapTileKey key) throws IOException {
        if (key.renderer() != MapRendererType.SURFACE || readFresh(key).isEmpty()) {
            request(key);
            return Optional.empty();
        }
        Optional<MapProjectedTile> projected = store.readProjected(key);
        if (projected.isEmpty()) request(key);
        return projected;
    }

    void readProjectedOrRequest(MapTileKey key, java.util.function.Consumer<MapProjectedTile> callback) {
        if (closed || key.renderer() != MapRendererType.SURFACE) return;
        projectedCallbacks.computeIfAbsent(key, ignored -> new java.util.concurrent.CopyOnWriteArraySet<>()).add(callback);
        try {
            renderer.execute(key, () -> {
                try {
                    Optional<BufferedImage> image = readFresh(key);
                    Optional<MapProjectedTile> projected = image.isPresent() ? store.readProjected(key) : Optional.empty();
                    if (projected.isPresent()) {
                        Schedulers.global(plugin, () -> deliverProjected(key, projected.get()));
                    } else {
                        Schedulers.global(plugin, () -> request(key));
                    }
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not read projected map tile " + key, exception);
                    Schedulers.global(plugin, () -> request(key));
                }
            });
        } catch (RejectedExecutionException exception) {
            projectedCallbacks.remove(key);
            plugin.getLogger().warning("Map render queue is full; dropping projected tile read " + key);
        }
    }

    private void deliverProjected(MapTileKey key, MapProjectedTile projected) {
        var callbacks = projectedCallbacks.remove(key);
        if (callbacks != null) callbacks.forEach(callback -> callback.accept(projected));
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

    void addSubscription(MapTileKey key) {
        subscribedTiles.add(key);
    }

    void removeSubscription(MapTileKey key) {
        subscribedTiles.remove(key);
    }

    void request(MapTileKey key) {
        if (closed) return;
        if (!Bukkit.isPrimaryThread()) {
            Schedulers.global(plugin, () -> request(key));
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
        String targetWorld = worldId(world);
        String targetDimension = dimensionId(world);
        WorldDimension worldDimension = new WorldDimension(targetWorld, targetDimension);
        AtomicLong worldRevision = worldRevisions.computeIfAbsent(worldDimension, ignored -> new AtomicLong());
        long changeRevision;
        boolean fullWorldInvalidation;
        synchronized (worldRevision) {
            changeRevision = worldRevision.incrementAndGet();
            long chunkKey = pack(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
            Map<Long, Long> chunks = changedChunks.computeIfAbsent(worldDimension, ignored -> new ConcurrentHashMap<>());
            chunks.put(chunkKey, changeRevision);
            fullWorldInvalidation = chunks.size() > 4096;
            if (fullWorldInvalidation) {
                chunks.clear();
                coarseRevisions.computeIfAbsent(worldDimension, ignored -> new AtomicLong()).set(changeRevision);
            }
        }

        Set<MapTileKey> affected = ConcurrentHashMap.newKeySet();
        subscribedTiles.stream().filter(key -> contains(key, worldDimension, blockX, blockZ)).forEach(affected::add);
        readyCallbacks.keySet().stream().filter(key -> contains(key, worldDimension, blockX, blockZ)).forEach(affected::add);
        queued.keySet().stream().filter(key -> contains(key, worldDimension, blockX, blockZ)).forEach(affected::add);
        MapWorldConfig config = configFor(world);
        MapTileKey defaultTile = MapTileKey.atBlock(targetWorld, targetDimension, config.renderer(),
                config.resolution(), 0, blockX, blockZ);
        affected.add(defaultTile);
        if (fullWorldInvalidation) {
            subscribedTiles.stream().filter(key -> matches(key, worldDimension)).forEach(affected::add);
            readyCallbacks.keySet().stream().filter(key -> matches(key, worldDimension)).forEach(affected::add);
            queued.keySet().stream().filter(key -> matches(key, worldDimension)).forEach(affected::add);
        }
        for (MapTileKey key : affected) {
            dirty.add(key);
            revisions.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
            if (key.equals(defaultTile) || subscribedTiles.contains(key) || readyCallbacks.containsKey(key)) {
                enqueueJob(key, world);
            }
        }
    }

    /** Jobs currently gathering chunk data; several advance in the same tick so one waiting job never blocks others. */
    private final java.util.List<Job> active = new java.util.ArrayList<>();
    private static final int MAX_ACTIVE_JOBS = 16;

    @Override
    public void run() {
        while (active.size() < MAX_ACTIVE_JOBS) {
            Job next = pending.poll();
            if (next == null) break;
            active.add(next);
        }
        int remaining = maxSamplesPerTick;
        for (java.util.Iterator<Job> iterator = active.iterator(); iterator.hasNext() && remaining > 0; ) {
            Job job = iterator.next();
            int processed = job.sample(remaining);
            remaining -= Math.max(processed, 1);
            if (job.complete()) {
                iterator.remove();
                renderAsync(job);
            }
        }
    }

    private void enqueueJob(MapTileKey key, World world) {
        if (closed || queued.containsKey(key)) return;
        Job job = new Job(key, world, revision(key));
        if (queued.putIfAbsent(key, job) == null) pending.add(job);
    }

    private Optional<BufferedImage> readFresh(MapTileKey key) throws IOException {
        if (dirty.contains(key)) return Optional.empty();
        WorldDimension worldDimension = new WorldDimension(key.worldId(), key.dimensionId());
        AtomicLong worldRevision = worldRevisions.computeIfAbsent(worldDimension, ignored -> new AtomicLong());
        long observedRevision;
        synchronized (worldRevision) {
            observedRevision = worldRevision.get();
            if (dirty.contains(key) || isStale(key, worldDimension, observedRevision)) return Optional.empty();
        }
        Optional<BufferedImage> image = store.read(key);
        if (image.isEmpty()) return image;
        synchronized (worldRevision) {
            long currentRevision = worldRevision.get();
            if (dirty.contains(key) || isStale(key, worldDimension, currentRevision)) return Optional.empty();
            tileRevisions.merge(key, currentRevision, Math::max);
        }
        return image;
    }

    private boolean isStale(MapTileKey key, WorldDimension worldDimension, long currentRevision) {
        long coarse = coarseRevisions.getOrDefault(worldDimension, new AtomicLong()).get();
        long renderedAt = tileRevisions.getOrDefault(key, coarse);
        if (renderedAt < coarse) return true;
        if (renderedAt >= currentRevision) return false;
        Map<Long, Long> chunks = changedChunks.get(worldDimension);
        if (chunks == null || chunks.isEmpty()) return false;
        int minChunkX = Math.floorDiv(key.minBlockX(), 16);
        int minChunkZ = Math.floorDiv(key.minBlockZ(), 16);
        int maxChunkX = (int) Math.floorDiv((long) key.minBlockX() + key.blocksPerTile() - 1, 16L);
        int maxChunkZ = (int) Math.floorDiv((long) key.minBlockZ() + key.blocksPerTile() - 1, 16L);
        long width = (long) maxChunkX - minChunkX + 1;
        long depth = (long) maxChunkZ - minChunkZ + 1;
        if (width * depth > 4096) return true;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (chunks.getOrDefault(pack(chunkX, chunkZ), 0L) > renderedAt) return true;
            }
        }
        return false;
    }

    private static boolean matches(MapTileKey key, WorldDimension worldDimension) {
        return key.worldId().equals(worldDimension.worldId())
                && key.dimensionId().equals(worldDimension.dimensionId());
    }

    private static boolean contains(MapTileKey key, WorldDimension worldDimension, int blockX, int blockZ) {
        if (!matches(key, worldDimension)) return false;
        long minX = key.minBlockX();
        long minZ = key.minBlockZ();
        return blockX >= minX && blockZ >= minZ
                && blockX < minX + key.blocksPerTile() && blockZ < minZ + key.blocksPerTile();
    }


    /** 26.x stores dimensions under dimensions/<namespace>/<path>/region; older layouts use region or DIM-1/DIM1. */
    private static java.nio.file.Path regionFolder(World world) {
        java.nio.file.Path folder = world.getWorldFolder().toPath();
        java.util.List<java.nio.file.Path> candidates = new java.util.ArrayList<>();
        for (java.nio.file.Path root : java.util.List.of(folder, folder.getParent() == null ? folder : folder.getParent().resolve(Bukkit.getWorlds().get(0).getName()))) {
            candidates.add(root.resolve("dimensions").resolve(world.getKey().getNamespace()).resolve(world.getKey().getKey()).resolve("region"));
        }
        switch (world.getEnvironment()) {
            case NETHER -> candidates.add(folder.resolve("DIM-1").resolve("region"));
            case THE_END -> candidates.add(folder.resolve("DIM1").resolve("region"));
            default -> candidates.add(folder.resolve("region"));
        }
        for (java.nio.file.Path candidate : candidates) {
            if (java.nio.file.Files.isDirectory(candidate)) return candidate;
        }
        return candidates.get(0);
    }

    private static Material material(String name) {
        if (name == null) return Material.AIR;
        Material material = Material.matchMaterial(name);
        return material == null ? Material.STONE : material;
    }

    private static String biomeKey(String name) {
        if (name == null) return "plains";
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    void shutdown() {
        closed = true;
        // The scheduled task is cancelled by the plugin.
        renderer.shutdown();
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
            renderer.execute(job.key, () -> {
                try {
                    job.fillSamples();
                    BufferedImage image = MapTileRenderers.forType(job.key.renderer())
                            .render(job.samples, job.key.resolution());
                    MapHeightTile heights = job.key.renderer() == MapRendererType.SURFACE
                            ? MapHeightTile.fromSurfaceSamples(job.samples, job.key.resolution()) : null;
                    MapProjectedTile projected = heights == null ? null
                            : MapTileProjector.project(image, heights, job.samples, job.key.resolution(), job.key.zoom());
                    store.write(job.key, image);
                    if (heights != null) {
                        store.writeHeights(job.key, heights);
                        store.writeProjected(job.key, projected);
                    }
                    tileRevisions.put(job.key, job.worldRevision);
                    WorldDimension worldDimension = new WorldDimension(job.key.worldId(), job.key.dimensionId());
                    AtomicLong worldRevision = worldRevisions.computeIfAbsent(worldDimension, ignored -> new AtomicLong());
                    boolean fresh;
                    synchronized (worldRevision) {
                        fresh = revision(job.key) == job.revision
                                && !isStale(job.key, worldDimension, worldRevision.get());
                    }
                    if (fresh) {
                        dirty.remove(job.key);
                        if (readyCallbacks.containsKey(job.key) || projectedCallbacks.containsKey(job.key) || !tileListeners.isEmpty()) {
                            byte[] png = encodePng(image);
                            Schedulers.global(plugin, () -> {
                                deliver(job.key, png);
                                if (projected != null) deliverProjected(job.key, projected);
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
                        Schedulers.global(plugin, () -> request(job.key));
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

    /** Kelp, seagrass and similar ocean-floor plants are the top block in water; draw them as water, not holes. */
    private static boolean underwaterPlant(String name) {
        return name.contains("KELP") || name.contains("SEAGRASS") || name.contains("SEA_PICKLE")
                || name.contains("CORAL") || name.contains("BUBBLE_COLUMN");
    }

    private static int colorFor(Material material) {
        String name = material.name();
        if (underwaterPlant(name)) return 0xff4f9ec4;
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

    private static int colorFor(Material material, String biomeKey) {
        int base = colorFor(material);
        String biome = biomeKey.toUpperCase(java.util.Locale.ROOT);
        if (material.name().contains("WATER") || underwaterPlant(material.name())) return tint(base, waterTint(biome));
        if (material.name().contains("ICE")) return tint(base, biome.contains("FROZEN") ? 0xffc5e4ed : 0xffa7d4e6);
        String block = material.name();
        boolean vegetation = block.contains("LEAVES") || block.contains("MOSS") || block.contains("GRASS")
                || block.contains("VINE") || block.contains("FERN") || block.contains("SAPLING");
        if (!vegetation) return base;
        if (biome.contains("SWAMP")) return tint(base, 0xff7a743c);
        if (biome.contains("MANGROVE")) return tint(base, 0xff58964e);
        if (biome.contains("BADLANDS") || biome.contains("SAVANNA") || biome.contains("DESERT")) {
            return tint(base, 0xffc2a94d);
        }
        if (biome.contains("SNOW") || biome.contains("FROZEN") || biome.contains("GROVE")) {
            return tint(base, 0xffa8c8ce);
        }
        if (biome.contains("JUNGLE") || biome.contains("BAMBOO")) return tint(base, 0xff63c45d);
        if (biome.contains("TAIGA") || biome.contains("OLD_GROWTH_PINE")) return tint(base, 0xff6d9376);
        if (biome.contains("CHERRY")) return tint(base, 0xffad9fbe);
        if (biome.contains("DARK_FOREST")) return tint(base, 0xff6e9272);
        return tint(base, 0xffa1c987);
    }

    private static int waterTint(String biome) {
        if (biome.contains("WARM_OCEAN")) return 0xff67c9b7;
        if (biome.contains("LUKEWARM")) return 0xff7bbec1;
        if (biome.contains("FROZEN") || biome.contains("COLD")) return 0xffb2d5e5;
        if (biome.contains("SWAMP") || biome.contains("MANGROVE")) return 0xff89965d;
        if (biome.contains("DEEP")) return 0xff718db5;
        return 0xff92b8d1;
    }

    private static int tint(int color, int tint) {
        int red = ((color >>> 16) & 0xff) * ((tint >>> 16) & 0xff) / 255;
        int green = ((color >>> 8) & 0xff) * ((tint >>> 8) & 0xff) / 255;
        int blue = (color & 0xff) * (tint & 0xff) / 255;
        return (color & 0xff000000) | (red << 16) | (green << 8) | blue;
    }

    private final class Job {
        private final MapTileKey key;
        private final World world;
        private final MapBlockSample[] samples;
        private final boolean[] loadedSamples;
        private final int side;
        private final long revision;
        private final java.util.List<Chunk> ticketed = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final long worldRevision;
        private int index;

        private final java.nio.file.Path regionFolder;
        private final int worldMinY;
        private final int worldHeight;

        private Job(MapTileKey key, World world, long revision) {
            this.key = key;
            this.world = world;
            this.regionFolder = regionFolder(world);
            this.worldMinY = world.getMinHeight();
            this.worldHeight = world.getMaxHeight() - world.getMinHeight();
            this.revision = revision;
            WorldDimension worldDimension = new WorldDimension(key.worldId(), key.dimensionId());
            this.worldRevision = worldRevisions.computeIfAbsent(worldDimension, ignored -> new AtomicLong()).get();
            this.side = key.samplesPerSide() + (key.renderer() == MapRendererType.SURFACE ? 2 : 0);
            this.samples = new MapBlockSample[side * side];
            this.loadedSamples = new boolean[samples.length];
        }


        /** Chunk snapshots taken on the server thread; sampling them happens on the render worker. */
        private final Map<Long, org.bukkit.ChunkSnapshot> snapshots = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger outstanding = new java.util.concurrent.atomic.AtomicInteger();
        private java.util.Iterator<Long> pendingChunks;
        private int caveY;

        /**
         * Server-thread step: after on-disk chunks are loaded, copy each needed chunk once as a snapshot (a bounded
         * number per tick). The block reads for every sample then run off the server thread in {@link #fillSamples()}.
         */
        private int sample(int budget) {
            if (pendingChunks == null) {
                java.util.LinkedHashSet<Long> needed = new java.util.LinkedHashSet<>();
                int spacing = key.blocksPerSample();
                int border = key.renderer() == MapRendererType.SURFACE ? 1 : 0;
                for (int i = 0; i < samples.length; i++) {
                    int x = key.minBlockX() + (i % side - border) * spacing;
                    int z = key.minBlockZ() + (i / side - border) * spacing;
                    needed.add(pack(x >> 4, z >> 4));
                }
                pendingChunks = needed.iterator();
                caveY = caveSliceY(world);
            }
            // Each snapshot is taken on the thread that owns the chunk (main thread on Paper, its region on Folia).
            while (pendingChunks.hasNext() && snapshotsInFlight.get() < maxSnapshotsInFlight) {
                long chunkKey = pendingChunks.next();
                int chunkX = (int) (chunkKey >> 32);
                int chunkZ = (int) chunkKey;
                snapshotsInFlight.incrementAndGet();
                outstanding.incrementAndGet();
                Schedulers.region(plugin, world, chunkX, chunkZ, () -> {
                    try {
                        if (world.isChunkLoaded(chunkX, chunkZ)) {
                            snapshots.put(chunkKey, world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(true, true, false));
                        }
                    } finally {
                        snapshotsInFlight.decrementAndGet();
                        outstanding.decrementAndGet();
                    }
                });
            }
            if (!pendingChunks.hasNext() && outstanding.get() == 0) {
                index = samples.length;
                releaseChunks();
                return budget;
            }
            return 0;
        }

        private final Map<Long, java.util.Optional<AnvilRegionReader.ChunkColumns>> savedChunks = new java.util.HashMap<>();

        private AnvilRegionReader.ChunkColumns savedChunk(long chunkKey, int chunkX, int chunkZ) {
            return savedChunks.computeIfAbsent(chunkKey, ignored -> java.util.Optional.ofNullable(
                    regionReaders.get().read(regionFolder, chunkX, chunkZ, worldMinY, worldHeight))).orElse(null);
        }

        private MapBlockSample sampleSaved(AnvilRegionReader.ChunkColumns saved, int localX, int localZ) {
            int surfaceY = saved.surfaceY(localX, localZ);
            if (key.renderer() == MapRendererType.CAVE) {
                Material floor = material(saved.blockName(localX, caveY, localZ));
                Material above = material(saved.blockName(localX, caveY + 1, localZ));
                boolean exposed = surfaceY > caveY + 2 && floor.isSolid() && !above.isSolid();
                return new MapBlockSample(colorFor(floor, biomeKey(saved.biomeName(localX, caveY, localZ))), caveY, exposed);
            }
            // WORLD_SURFACE counts small decorations (bushes, leaf litter...) that have no map color; look below them.
            int y = surfaceY;
            Material top = material(saved.blockName(localX, y, localZ));
            String biome = biomeKey(saved.biomeName(localX, y, localZ));
            int color = colorFor(top, biome);
            for (int depth = 0; depth < 8 && (color >>> 24) == 0 && y > worldMinY; depth++) {
                y--;
                top = material(saved.blockName(localX, y, localZ));
                color = colorFor(top, biome);
            }
            return new MapBlockSample(color, y, false);
        }

        /** Render-worker step: read every sample from the snapshots, or from region files for unloaded chunks. */
        private void fillSamples() {
            int spacing = key.blocksPerSample();
            int border = key.renderer() == MapRendererType.SURFACE ? 1 : 0;
            int minHeight = world.getMinHeight();
            for (int i = 0; i < samples.length; i++) {
                int x = key.minBlockX() + (i % side - border) * spacing;
                int z = key.minBlockZ() + (i / side - border) * spacing;
                long chunkKey = pack(x >> 4, z >> 4);
                org.bukkit.ChunkSnapshot snapshot = snapshots.get(chunkKey);
                if (snapshot == null) {
                    AnvilRegionReader.ChunkColumns saved = diskLoading ? savedChunk(chunkKey, x >> 4, z >> 4) : null;
                    if (saved == null) {
                        samples[i] = new MapBlockSample(0x00000000, minHeight, false);
                        loadedSamples[i] = false;
                    } else {
                        samples[i] = sampleSaved(saved, x & 15, z & 15);
                        loadedSamples[i] = true;
                    }
                    continue;
                }
                int localX = x & 15;
                int localZ = z & 15;
                int surfaceY = snapshot.getHighestBlockYAt(localX, localZ);
                if (key.renderer() == MapRendererType.CAVE) {
                    Material floor = snapshot.getBlockType(localX, caveY, localZ);
                    Material above = snapshot.getBlockType(localX, caveY + 1, localZ);
                    boolean exposed = surfaceY > caveY + 2 && floor.isSolid() && !above.isSolid();
                    samples[i] = new MapBlockSample(colorFor(floor, snapshot.getBiome(localX, caveY, localZ).getKey().getKey()), caveY, exposed);
                } else {
                    Material top = snapshot.getBlockType(localX, surfaceY, localZ);
                    samples[i] = new MapBlockSample(colorFor(top, snapshot.getBiome(localX, surfaceY, localZ).getKey().getKey()), surfaceY, false);
                }
                loadedSamples[i] = true;
            }
            if (key.renderer() == MapRendererType.SURFACE) fillMissingBorderHeights();
            snapshots.clear();
            savedChunks.clear();
        }

        private void releaseChunks() {
            java.util.List<Chunk> chunks;
            synchronized (ticketed) {
                chunks = new java.util.ArrayList<>(ticketed);
                ticketed.clear();
            }
            for (Chunk chunk : chunks) {
                Schedulers.region(plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> chunk.removePluginChunkTicket(plugin));
            }
        }

        private void fillMissingBorderHeights() {
            for (int sampleZ = 0; sampleZ < side; sampleZ++) {
                for (int sampleX = 0; sampleX < side; sampleX++) {
                    if (sampleX > 0 && sampleX < side - 1 && sampleZ > 0 && sampleZ < side - 1) continue;
                    int index = sampleZ * side + sampleX;
                    if (loadedSamples[index]) continue;
                    int innerX = Math.max(1, Math.min(side - 2, sampleX));
                    int innerZ = Math.max(1, Math.min(side - 2, sampleZ));
                    int height = samples[innerZ * side + innerX].height();
                    samples[index] = new MapBlockSample(0x00000000, height, false);
                }
            }
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

    private record WorldDimension(String worldId, String dimensionId) {}
}
