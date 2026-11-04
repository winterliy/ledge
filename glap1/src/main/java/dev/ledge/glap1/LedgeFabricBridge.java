package dev.ledge.glap1;

import dev.ledge.common.MapResolution;
import dev.ledge.common.MapTileKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded request/fragment protocol shared with the Fabric client on ledge:map. */
final class LedgeFabricBridge implements PluginMessageListener, Listener {
    static final String CHANNEL = "ledge:map";
    private static final int FRAGMENT_BYTES = 24_000;
    private static final int MAX_TILE_BYTES = 1_000_000;
    private final JavaPlugin plugin;
    private final MapTileEngine tiles;
    private final Map<UUID, Integer> requests = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> activeClients = ConcurrentHashMap.newKeySet();
    private final Map<MapTileKey, java.util.Set<UUID>> subscribers = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.Set<MapTileKey>> playerTiles = new ConcurrentHashMap<>();
    private final java.util.function.BiConsumer<MapTileKey, byte[]> tileListener = this::publishTile;

    LedgeFabricBridge(JavaPlugin plugin, MapTileEngine tiles) {
        this.plugin = plugin;
        this.tiles = tiles;
    }

    void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        tiles.addTileListener(tileListener);
    }

    void unregister() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        tiles.removeTileListener(tileListener);
        subscribers.clear();
        playerTiles.clear();
        requests.clear();
        activeClients.clear();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || message.length == 0) return;
        if (message.length == 1 && message[0] == 4) {
            sendSettings(player);
            return;
        }
        if (message[0] == 3) {
            unsubscribe(player.getUniqueId());
            return;
        }
        if (message.length != 12 || message[0] != 1) return;
        activeClients.add(player.getUniqueId());
        int rendererIndex = Byte.toUnsignedInt(message[1]);
        int resolutionIndex = Byte.toUnsignedInt(message[2]);
        int zoom = Byte.toUnsignedInt(message[3]);
        if (rendererIndex >= dev.ledge.common.MapRendererType.values().length
                || resolutionIndex >= MapResolution.values().length || zoom > MapTileKey.MAX_ZOOM) return;
        World world = player.getWorld();
        var config = MapTileEngine.configFor(world);
        // Server-owned per-world configuration is authoritative; client enum values are advisory.
        int tileX = readInt(message, 4);
        int tileZ = readInt(message, 8);
        MapTileKey key;
        try {
            key = new MapTileKey(MapTileEngine.worldId(world), MapTileEngine.dimensionId(world),
                    config.renderer(), config.resolution(), zoom, tileX, tileZ);
        } catch (IllegalArgumentException exception) {
            return;
        }
        int blocks = key.blocksPerTile();
        int playerTileX = Math.floorDiv(player.getLocation().getBlockX(), blocks);
        int playerTileZ = Math.floorDiv(player.getLocation().getBlockZ(), blocks);
        if (Math.abs((long) tileX - playerTileX) > 8 || Math.abs((long) tileZ - playerTileZ) > 8) return;
        int count = requests.merge(player.getUniqueId(), 1, Integer::sum);
        if (count > 64) {
            requests.remove(player.getUniqueId());
            return;
        }
        tiles.readOrRequest(key, png -> {
            requests.computeIfPresent(player.getUniqueId(), (id, current) -> current <= 1 ? null : current - 1);
            if (player.isOnline() && activeClients.contains(player.getUniqueId())) {
                sendTile(player, key, png);
                subscribe(player.getUniqueId(), key);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        requests.remove(id);
        activeClients.remove(id);
        subscribers.values().forEach(ids -> ids.remove(id));
        subscribers.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        playerTiles.remove(id);
    }

    private void unsubscribe(UUID playerId) {
        activeClients.remove(playerId);
        requests.remove(playerId);
        var keys = playerTiles.remove(playerId);
        if (keys == null) return;
        for (MapTileKey key : keys) {
            var ids = subscribers.get(key);
            if (ids == null) continue;
            ids.remove(playerId);
            if (ids.isEmpty()) subscribers.remove(key, ids);
        }
    }

    private void subscribe(UUID playerId, MapTileKey key) {
        var keys = playerTiles.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet());
        if (!keys.add(key)) return;
        subscribers.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
        if (keys.size() > 64) {
            var iterator = keys.iterator();
            if (iterator.hasNext()) {
                MapTileKey expired = iterator.next();
                iterator.remove();
                var ids = subscribers.get(expired);
                if (ids != null) {
                    ids.remove(playerId);
                    if (ids.isEmpty()) subscribers.remove(expired, ids);
                }
            }
        }
    }

    private void publishTile(MapTileKey key, byte[] png) {
        var ids = subscribers.get(key);
        if (ids == null) return;
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline() && activeClients.contains(id)) sendTile(player, key, png);
        }
    }

    private void sendTile(Player player, MapTileKey key, byte[] png) {
        if (png.length == 0 || png.length > MAX_TILE_BYTES) return;
        int parts = (png.length + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES;
        int transfer = UUID.randomUUID().hashCode();
        for (int part = 0; part < parts; part++) {
            int start = part * FRAGMENT_BYTES;
            int length = Math.min(FRAGMENT_BYTES, png.length - start);
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(length + 64);
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeByte(2);
                out.writeByte(key.renderer().ordinal());
                out.writeByte(key.resolution().ordinal());
                out.writeByte(key.zoom());
                out.writeInt(key.tileX());
                out.writeInt(key.tileZ());
                out.writeUTF(key.dimensionId());
                out.writeInt(transfer);
                out.writeShort(part);
                out.writeShort(parts);
                out.writeInt(png.length);
                out.write(png, start, length);
                player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not encode map tile packet: " + exception.getMessage());
                return;
            }
        }
    }

    private void sendSettings(Player player) {
        var config = MapTileEngine.configFor(player.getWorld());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(48);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(4);
            out.writeByte(config.renderer().ordinal());
            out.writeByte(config.resolution().ordinal());
            out.writeUTF(config.dimensionId());
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not encode map settings: " + exception.getMessage());
        }
    }

    private static int readInt(byte[] value, int offset) {
        return (Byte.toUnsignedInt(value[offset]) << 24) | (Byte.toUnsignedInt(value[offset + 1]) << 16)
                | (Byte.toUnsignedInt(value[offset + 2]) << 8) | Byte.toUnsignedInt(value[offset + 3]);
    }
}
