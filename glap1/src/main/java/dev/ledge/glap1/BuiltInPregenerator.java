package dev.ledge.glap1;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Built-in terrain pre-generator modelled on Chunky's task flow (GPL-3.0, github.com/pop4959/Chunky):
 * square or circle selections around a center, generated ring by ring from the center outward with a bounded
 * number of async chunk loads, pause/continue/cancel, and progress saved to {@code pregen.yml} so tasks survive
 * a restart (they come back paused). Each finished chunk refreshes the map tiles that cover it.
 */
final class BuiltInPregenerator implements Pregenerator {
    private static final int MAX_IN_FLIGHT = 16;

    private final JavaPlugin plugin;
    private final MapTileEngine tiles;
    private final File stateFile;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final TaskHandle saver;

    BuiltInPregenerator(JavaPlugin plugin, MapTileEngine tiles) {
        this.plugin = plugin;
        this.tiles = tiles;
        this.stateFile = new File(plugin.getDataFolder(), "pregen.yml");
        load();
        this.saver = Schedulers.globalTimer(plugin, this::save, 600L, 600L);
    }

    @Override
    public String start(String worldName, double radius, String shape, Double centerX, Double centerZ) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return "Unknown world: " + worldName;
        if (radius < 16 || radius > 60_000) return "Radius must be between 16 and 60000 blocks.";
        String normalizedShape = shape == null ? "square" : shape.toLowerCase(Locale.ROOT);
        if (!normalizedShape.equals("square") && !normalizedShape.equals("circle")) return "Shape must be square or circle.";
        if (tasks.containsKey(worldName)) return "Pre-generation already exists for " + worldName + ". Use continue or cancel.";
        double x = centerX != null ? centerX : world.getSpawnLocation().getX();
        double z = centerZ != null ? centerZ : world.getSpawnLocation().getZ();
        Task task = new Task(worldName, normalizedShape, x, z, radius, 0, 0, 0);
        tasks.put(worldName, task);
        task.run();
        save();
        return "Started " + normalizedShape + " pre-generation for " + worldName + " around " + (int) x + ", " + (int) z
                + " (radius " + (int) radius + ", about " + task.total + " chunks).";
    }

    @Override
    public String pause(String world) {
        Task task = tasks.get(world);
        if (task == null || task.handle == null) return "No running pre-generation for " + world + ".";
        task.stop();
        save();
        return "Paused pre-generation for " + world + " at " + task.percent() + "%.";
    }

    @Override
    public String resume(String world) {
        Task task = tasks.get(world);
        if (task == null) return "No saved pre-generation for " + world + ".";
        if (task.handle != null) return "Pre-generation is already running for " + world + ".";
        if (Bukkit.getWorld(world) == null) return "World " + world + " is not loaded.";
        task.run();
        return "Continuing pre-generation for " + world + " from " + task.percent() + "%.";
    }

    @Override
    public String cancel(String world) {
        Task task = tasks.remove(world);
        if (task == null) return "No pre-generation to cancel for " + world + ".";
        task.stop();
        save();
        return "Cancelled pre-generation for " + world + " at " + task.percent() + "%.";
    }

    @Override
    public String status() {
        if (tasks.isEmpty()) return "No pre-generation tasks.";
        StringBuilder text = new StringBuilder();
        for (Task task : tasks.values()) {
            if (!text.isEmpty()) text.append('\n');
            text.append(task.world).append(": ").append(task.percent()).append("% (")
                    .append(task.done.get()).append('/').append(task.total).append(" chunks, ")
                    .append(task.handle == null ? "paused" : "running").append(')');
        }
        return text.toString();
    }

    void shutdown() {
        tasks.values().forEach(Task::stop);
        save();
        saver.cancel();
    }

    private void load() {
        if (!stateFile.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        ConfigurationSection section = yaml.getConfigurationSection("tasks");
        if (section == null) return;
        for (String world : section.getKeys(false)) {
            ConfigurationSection row = section.getConfigurationSection(world);
            if (row == null) continue;
            tasks.put(world, new Task(world, row.getString("shape", "square"), row.getDouble("centerX"),
                    row.getDouble("centerZ"), row.getDouble("radius"), row.getInt("ring"), row.getInt("step"),
                    row.getLong("done")));
            plugin.getLogger().info("Saved pre-generation for " + world + " is paused; run /ledge pregen continue " + world + ".");
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Task task : tasks.values()) {
            String base = "tasks." + task.world + ".";
            yaml.set(base + "shape", task.shape);
            yaml.set(base + "centerX", task.centerBlockX);
            yaml.set(base + "centerZ", task.centerBlockZ);
            yaml.set(base + "radius", task.radiusBlocks);
            yaml.set(base + "ring", task.safeRing);
            yaml.set(base + "step", task.safeStep);
            yaml.set(base + "done", task.done.get());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(stateFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save pre-generation progress: " + exception.getMessage());
        }
    }

    private final class Task {
        private final String world;
        private final String shape;
        private final double centerBlockX;
        private final double centerBlockZ;
        private final double radiusBlocks;
        private final int centerX;
        private final int centerZ;
        private final int radius;
        private final long total;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicLong done;
        private TaskHandle handle;
        private int ring;
        private int step;
        /** Position up to which every issued chunk has finished; resuming restarts here. */
        private int safeRing;
        private int safeStep;
        private int lastLoggedPercent;

        private Task(String world, String shape, double centerBlockX, double centerBlockZ, double radiusBlocks,
                     int ring, int step, long done) {
            this.world = world;
            this.shape = shape;
            this.centerBlockX = centerBlockX;
            this.centerBlockZ = centerBlockZ;
            this.radiusBlocks = radiusBlocks;
            this.centerX = (int) Math.floor(centerBlockX) >> 4;
            this.centerZ = (int) Math.floor(centerBlockZ) >> 4;
            this.radius = (int) Math.ceil(radiusBlocks / 16.0);
            long side = 2L * radius + 1;
            this.total = shape.equals("circle") ? Math.max(1, Math.round(Math.PI * radius * radius)) : side * side;
            this.ring = ring;
            this.step = step;
            this.safeRing = ring;
            this.safeStep = step;
            this.done = new AtomicLong(done);
            this.lastLoggedPercent = percent() / 10 * 10;
        }

        private int percent() {
            return (int) Math.min(100, done.get() * 100 / Math.max(1, total));
        }

        private void run() {
            ring = safeRing;
            step = safeStep;
            handle = Schedulers.globalTimer(plugin, this::tick, 1L, 1L);
        }

        private void stop() {
            if (handle != null) handle.cancel();
            handle = null;
        }

        private void tick() {
            World bukkitWorld = Bukkit.getWorld(world);
            if (bukkitWorld == null) {
                stop();
                return;
            }
            if (inFlight.get() == 0) {
                safeRing = ring;
                safeStep = step;
            }
            if (Schedulers.averageTickMillis() > 45.0) return;
            while (inFlight.get() < MAX_IN_FLIGHT) {
                int[] chunk = nextChunk();
                if (chunk == null) break;
                inFlight.incrementAndGet();
                int chunkX = chunk[0];
                int chunkZ = chunk[1];
                bukkitWorld.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((loaded, failure) -> {
                    inFlight.decrementAndGet();
                    done.incrementAndGet();
                    tiles.invalidate(bukkitWorld, (chunkX << 4) + 8, (chunkZ << 4) + 8);
                });
            }
            int percent = percent();
            if (percent >= lastLoggedPercent + 10) {
                lastLoggedPercent = percent / 10 * 10;
                plugin.getLogger().info("Pre-generation " + world + ": " + percent + "% (" + done.get() + "/" + total + " chunks)");
            }
            if (ring > radius && inFlight.get() == 0) {
                stop();
                tasks.remove(world);
                save();
                plugin.getLogger().info("Pre-generation complete: " + world);
            }
        }

        /** Walks square rings around the center (ring 0 is the center chunk); circle tasks skip corners. */
        private int[] nextChunk() {
            while (ring <= radius) {
                int x;
                int z;
                if (ring == 0) {
                    x = 0;
                    z = 0;
                    ring = 1;
                    step = 0;
                } else {
                    int side = ring * 2;
                    int edge = step / side;
                    int offset = step % side;
                    switch (edge) {
                        case 0 -> { x = -ring + offset; z = -ring; }
                        case 1 -> { x = ring; z = -ring + offset; }
                        case 2 -> { x = ring - offset; z = ring; }
                        default -> { x = -ring; z = ring - offset; }
                    }
                    step++;
                    if (step >= side * 4) {
                        ring++;
                        step = 0;
                    }
                }
                if (shape.equals("circle") && (long) x * x + (long) z * z > (long) radius * radius) continue;
                return new int[]{centerX + x, centerZ + z};
            }
            return null;
        }
    }
}
