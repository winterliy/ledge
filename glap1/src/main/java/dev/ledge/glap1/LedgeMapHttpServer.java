package dev.ledge.glap1;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ledge.common.MapRendererType;
import dev.ledge.common.MapResolution;
import dev.ledge.common.MapTileKey;
import dev.ledge.common.MapWorldConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Loopback-first web map. Fabric clients do not use this server. */
final class LedgeMapHttpServer {
    private final JavaPlugin plugin;
    private final MapTileEngine tileEngine;
    private HttpServer server;
    private ThreadPoolExecutor requests;
    private volatile byte[] worldsSnapshot = "[]".getBytes(StandardCharsets.UTF_8);
    private volatile byte[] playersSnapshot = "[]".getBytes(StandardCharsets.UTF_8);
    private volatile Map<String, String> knownWorlds = Map.of();

    LedgeMapHttpServer(JavaPlugin plugin, MapTileEngine tileEngine) {
        this.plugin = plugin;
        this.tileEngine = tileEngine;
    }

    void start() throws IOException {
        if (!plugin.getConfig().getBoolean("web.enabled", true)) {
            plugin.getLogger().info("Ledge web map is disabled.");
            return;
        }
        String host = plugin.getConfig().getString("web.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("web.port", 8123);
        InetAddress address = InetAddress.getByName(host);
        if (!address.isLoopbackAddress() && !plugin.getConfig().getBoolean("web.allow-external", false)) {
            throw new IOException("External web binding requires web.allow-external: true");
        }
        server = HttpServer.create(new InetSocketAddress(address, port), 32);
        requests = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), runnable -> {
            Thread thread = new Thread(runnable, "Ledge-Web-" + port);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(requests);
        server.createContext("/", this::handle);
        refreshSnapshots();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshSnapshots, 20L, 20L);
        server.start();
        plugin.getLogger().info("Ledge map: http://" + host + ":" + port + "/");
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (requests != null) {
            requests.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain; charset=utf-8", "GET only");
            } else if ("/".equals(path)) {
                byte[] page = plugin.getResource("web/index.html").readAllBytes();
                respond(exchange, 200, "text/html; charset=utf-8", page);
            } else if ("/api/worlds".equals(path)) {
                respond(exchange, 200, "application/json; charset=utf-8", worldsSnapshot);
            } else if ("/api/players".equals(path)) {
                respond(exchange, 200, "application/json; charset=utf-8", playersSnapshot);
            } else if ("/tiles".equals(path)) {
                serveTile(exchange, query(exchange.getRequestURI().getRawQuery()));
            } else if ("/heights".equals(path)) {
                serveHeights(exchange, query(exchange.getRequestURI().getRawQuery()));
            } else {
                respond(exchange, 404, "text/plain; charset=utf-8", "Not found");
            }
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, "text/plain; charset=utf-8", exception.getMessage());
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Web map request failed", exception);
            respond(exchange, 500, "text/plain; charset=utf-8", "Map request failed");
        } finally {
            exchange.close();
        }
    }

    private void serveTile(HttpExchange exchange, Map<String, String> query) throws IOException {
        String worldId = required(query, "world");
        String dimension = required(query, "dimension");
        if (!knownWorlds.containsKey(worldId + "\u0000" + dimension)) {
            respond(exchange, 404, "text/plain; charset=utf-8", "Unknown world or dimension");
            return;
        }
        MapRendererType renderer = enumValue(MapRendererType.class, query.getOrDefault("renderer", "SURFACE"));
        MapResolution resolution = enumValue(MapResolution.class, query.getOrDefault("resolution", "STANDARD"));
        int zoom = number(query, "zoom", 0, MapTileKey.MIN_ZOOM, MapTileKey.MAX_ZOOM);
        int x = number(query, "x", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        int z = number(query, "z", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        MapTileKey key = new MapTileKey(worldId, dimension, renderer, resolution, zoom, x, z);
        var tile = tileEngine.read(key);
        if (tile.isEmpty()) {
            exchange.getResponseHeaders().set("Retry-After", "1");
            respond(exchange, 202, "text/plain; charset=utf-8", "Rendering");
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(12_000);
        if (!ImageIO.write(tile.get(), "png", bytes)) {
            throw new IOException("PNG writer unavailable");
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        respond(exchange, 200, "image/png", bytes.toByteArray());
    }

    private void serveHeights(HttpExchange exchange, Map<String, String> query) throws IOException {
        String worldId = required(query, "world");
        String dimension = required(query, "dimension");
        if (!knownWorlds.containsKey(worldId + "\u0000" + dimension)) {
            respond(exchange, 404, "text/plain; charset=utf-8", "Unknown world or dimension");
            return;
        }
        MapRendererType renderer = enumValue(MapRendererType.class, query.getOrDefault("renderer", "SURFACE"));
        if (renderer != MapRendererType.SURFACE) {
            respond(exchange, 400, "text/plain; charset=utf-8", "Height data is available for surface maps only");
            return;
        }
        MapResolution resolution = enumValue(MapResolution.class, query.getOrDefault("resolution", "STANDARD"));
        int zoom = number(query, "zoom", 0, MapTileKey.MIN_ZOOM, MapTileKey.MAX_ZOOM);
        int x = number(query, "x", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        int z = number(query, "z", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        MapTileKey key = new MapTileKey(worldId, dimension, renderer, resolution, zoom, x, z);
        var heights = tileEngine.readHeights(key);
        if (heights.isEmpty()) {
            exchange.getResponseHeaders().set("Retry-After", "1");
            respond(exchange, 202, "text/plain; charset=utf-8", "Rendering");
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        respond(exchange, 200, "application/octet-stream", heights.get().toBytes());
    }

    private void refreshSnapshots() {
        Map<String, String> currentWorlds = new ConcurrentHashMap<>();
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (World world : Bukkit.getWorlds()) {
            String dimensionId = MapTileEngine.dimensionId(world);
            String worldId = MapTileEngine.worldId(world);
            currentWorlds.put(worldId + "\u0000" + dimensionId, world.getName());
            if (!first) json.append(',');
            first = false;
            MapWorldConfig config = MapTileEngine.configFor(world);
            json.append("{\"world\":\"").append(escape(worldId))
                    .append("\",\"dimension\":\"").append(escape(MapTileEngine.dimensionId(world)))
                    .append("\",\"label\":\"").append(escape(world.getName() + " · " + world.getEnvironment().name()))
                    .append("\",\"x\":").append(world.getSpawnLocation().getBlockX())
                    .append(",\"z\":").append(world.getSpawnLocation().getBlockZ())
                    .append(",\"renderer\":\"").append(config.renderer().name())
                    .append("\",\"resolution\":\"").append(config.resolution().name()).append("\"}");
        }
        worldsSnapshot = json.append(']').toString().getBytes(StandardCharsets.UTF_8);
        knownWorlds = Map.copyOf(currentWorlds);
        playersSnapshot = currentPlayersJson();
    }

    private byte[] currentPlayersJson() {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"name\":\"").append(escape(player.getName()))
                    .append("\",\"world\":\"").append(escape(MapTileEngine.worldId(player.getWorld())))
                    .append("\",\"dimension\":\"").append(escape(MapTileEngine.dimensionId(player.getWorld())))
                    .append("\",\"x\":").append(player.getLocation().getX())
                    .append(",\"z\":").append(player.getLocation().getZ()).append('}');
        }
        return json.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.length() > 2048) return result;
        for (String part : rawQuery.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            result.putIfAbsent(key, value);
        }
        return result;
    }

    private static String required(Map<String, String> query, String key) {
        String value = query.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static int number(Map<String, String> query, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(query.getOrDefault(key, Integer.toString(fallback)));
            if (value < min || value > max) throw new IllegalArgumentException("Invalid " + key);
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + key);
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName());
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("<", "\\u003c");
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
