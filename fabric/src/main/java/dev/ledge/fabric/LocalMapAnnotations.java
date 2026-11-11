package dev.ledge.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Client-local annotations, kept separate for each multiplayer server, world, and dimension. */
final class LocalMapAnnotations {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Marker> MARKERS = new ArrayList<>();
    private static final List<Area> AREAS = new ArrayList<>();
    private static Path currentFile;

    private LocalMapAnnotations() {}

    static void load(Path gameDirectory, String server, String worldId, String dimension) {
        String serverKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(server.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String worldKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(worldId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        currentFile = gameDirectory.resolve("config/ledge/annotations")
                .resolve(serverKey + "-" + worldKey + "-" + dimension + ".json");
        MARKERS.clear();
        AREAS.clear();
        if (!Files.isRegularFile(currentFile)) return;
        try (var reader = Files.newBufferedReader(currentFile)) {
            Snapshot snapshot = GSON.fromJson(reader, Snapshot.class);
            if (snapshot != null) {
                if (snapshot.markers() != null) MARKERS.addAll(snapshot.markers());
                if (snapshot.areas() != null) AREAS.addAll(snapshot.areas());
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    static List<Marker> markers() { return Compat.copyOf(MARKERS); }
    static List<Area> areas() { return Compat.copyOf(AREAS); }

    static void addMarker(Marker marker) {
        MARKERS.add(marker);
        save();
    }

    static void addArea(Area area) {
        AREAS.add(area);
        save();
    }

    static void replaceMarker(Marker old, Marker updated) {
        int index = MARKERS.indexOf(old);
        if (index >= 0) { MARKERS.set(index, updated); save(); }
    }

    static void removeMarker(Marker marker) {
        if (MARKERS.remove(marker)) save();
    }

    static void replaceArea(Area old, Area updated) {
        int index = AREAS.indexOf(old);
        if (index >= 0) { AREAS.set(index, updated); save(); }
    }

    static void removeArea(Area area) {
        if (AREAS.remove(area)) save();
    }

    private static void save() {
        if (currentFile == null) return;
        try {
            Files.createDirectories(currentFile.getParent());
            try (var writer = Files.newBufferedWriter(currentFile)) {
                GSON.toJson(new Snapshot(MARKERS, AREAS), writer);
            }
        } catch (IOException ignored) {
        }
    }

    @com.github.bsideup.jabel.Desugar

    record Marker(int x, int z, String name, String color) {}
    @com.github.bsideup.jabel.Desugar
    record Point(int x, int z) {}
    @com.github.bsideup.jabel.Desugar
    record Area(int minX, int minZ, int maxX, int maxZ, String name, String color, boolean filled, List<Point> points) {
        Area(int minX, int minZ, int maxX, int maxZ, String name, String color, boolean filled) {
            this(minX, minZ, maxX, maxZ, name, color, filled, Compat.listOf(
                    new Point(minX, minZ), new Point(maxX, minZ), new Point(maxX, maxZ), new Point(minX, maxZ)));
        }

        Area {
            points = points == null ? Compat.listOf() : Compat.copyOf(points);
            if (points.size() > 4096) throw new IllegalArgumentException("an area can contain at most 4096 points");
        }
    }
    @com.github.bsideup.jabel.Desugar
    private record Snapshot(List<Marker> markers, List<Area> areas) {}
}
