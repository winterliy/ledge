package dev.ledge.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Client-local annotations, kept separate for each multiplayer server and dimension. */
final class LocalMapAnnotations {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Marker> MARKERS = new ArrayList<>();
    private static final List<Area> AREAS = new ArrayList<>();
    private static Path currentFile;

    private LocalMapAnnotations() {}

    static void load(Path gameDirectory, String server, String dimension) {
        String serverKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(server.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        currentFile = gameDirectory.resolve("config/ledge/annotations")
                .resolve(serverKey + "-" + dimension + ".json");
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

    static List<Marker> markers() { return List.copyOf(MARKERS); }
    static List<Area> areas() { return List.copyOf(AREAS); }

    static void addMarker(Marker marker) {
        MARKERS.add(marker);
        save();
    }

    static void addArea(Area area) {
        AREAS.add(area);
        save();
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

    record Marker(int x, int z, String name, String color) {}
    record Area(int minX, int minZ, int maxX, int maxZ, String name, String color, boolean filled) {}
    private record Snapshot(List<Marker> markers, List<Area> areas) {}
}
