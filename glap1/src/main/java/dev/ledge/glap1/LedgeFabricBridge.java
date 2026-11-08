package dev.ledge.glap1;

import dev.ledge.common.MapResolution;
import dev.ledge.common.MapProjectedTile;
import dev.ledge.common.MapTileKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
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
        subscribers.keySet().forEach(tiles::removeSubscription);
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
        if (message[0] != 1) return;
        activeClients.add(player.getUniqueId());
        String targetWorldId;
        String targetDimension;
        int zoom;
        int tileX;
        int tileZ;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            in.readUnsignedByte();
            int rendererIndex = in.readUnsignedByte();
            int resolutionIndex = in.readUnsignedByte();
            zoom = in.readUnsignedByte();
            tileX = in.readInt();
            tileZ = in.readInt();
            targetWorldId = in.readUTF();
            targetDimension = in.readUTF();
            if (in.available() != 0 || rendererIndex >= dev.ledge.common.MapRendererType.values().length
                    || resolutionIndex >= MapResolution.values().length || zoom > MapTileKey.MAX_ZOOM
                    || targetWorldId.length() > 96 || targetDimension.length() > 96) return;
        } catch (IOException exception) {
            return;
        }
        World world = Bukkit.getWorlds().stream()
                .filter(candidate -> MapTileEngine.worldId(candidate).equals(targetWorldId)
                        && MapTileEngine.dimensionId(candidate).equals(targetDimension))
                .findFirst().orElse(null);
        if (world == null) return;
        var config = MapTileEngine.configFor(world);
        // Server-owned per-world configuration is authoritative; client enum values are advisory.
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
                if (key.renderer() == dev.ledge.common.MapRendererType.SURFACE) {
                    tiles.readProjectedOrRequest(key, projected -> {
                        sendProjected(player, key, projected);
                        Schedulers.global(plugin, () -> {
                            if (player.isOnline() && activeClients.contains(player.getUniqueId())) {
                                subscribe(player.getUniqueId(), key);
                            }
                        });
                    });
                } else {
                    subscribe(player.getUniqueId(), key);
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        requests.remove(id);
        activeClients.remove(id);
        var keys = playerTiles.remove(id);
        if (keys != null) {
            for (MapTileKey key : keys) {
                var ids = subscribers.get(key);
                if (ids == null) continue;
                ids.remove(id);
                if (ids.isEmpty() && subscribers.remove(key, ids)) tiles.removeSubscription(key);
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (activeClients.contains(event.getPlayer().getUniqueId())) sendSettings(event.getPlayer());
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
            if (ids.isEmpty() && subscribers.remove(key, ids)) tiles.removeSubscription(key);
        }
    }

    private void subscribe(UUID playerId, MapTileKey key) {
        var keys = playerTiles.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet());
        if (!keys.add(key)) return;
        subscribers.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
        tiles.addSubscription(key);
        if (keys.size() > 64) {
            var iterator = keys.iterator();
            if (iterator.hasNext()) {
                MapTileKey expired = iterator.next();
                iterator.remove();
                var ids = subscribers.get(expired);
                if (ids != null) {
                    ids.remove(playerId);
                    if (ids.isEmpty() && subscribers.remove(expired, ids)) tiles.removeSubscription(expired);
                }
            }
        }
    }

    private void publishTile(MapTileKey key, byte[] png) {
        var ids = subscribers.get(key);
        if (ids == null) return;
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline() && activeClients.contains(id)) {
                sendTile(player, key, png);
                tiles.readProjectedOrRequest(key, projected -> sendProjected(player, key, projected));
            }
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
                out.writeUTF(key.worldId());
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

    private void sendProjected(Player player, MapTileKey key, MapProjectedTile projected) {
        if (!player.isOnline() || !activeClients.contains(player.getUniqueId())) return;
        try {
            ByteArrayOutputStream pngBytes = new ByteArrayOutputStream(24_000);
            pngBytes.write(dev.ledge.common.FastPng.encode(projected.image()));
            byte[] png = pngBytes.toByteArray();
            if (png.length == 0 || png.length > MAX_TILE_BYTES) return;
            int parts = (png.length + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES;
            int transfer = UUID.randomUUID().hashCode();
            for (int part = 0; part < parts; part++) {
                int start = part * FRAGMENT_BYTES;
                int length = Math.min(FRAGMENT_BYTES, png.length - start);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(length + 72);
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeByte(7);
                out.writeByte(key.renderer().ordinal());
                out.writeByte(key.resolution().ordinal());
                out.writeByte(key.zoom());
                out.writeInt(key.tileX());
                out.writeInt(key.tileZ());
                out.writeUTF(key.worldId());
                out.writeUTF(key.dimensionId());
                out.writeInt(projected.topOffset());
                out.writeInt(transfer);
                out.writeShort(part);
                out.writeShort(parts);
                out.writeInt(png.length);
                out.write(png, start, length);
                player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not encode projected map tile: " + exception.getMessage());
        }
    }

    private void sendSettings(Player player) {
        var config = MapTileEngine.configFor(player.getWorld());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(4);
            out.writeByte(config.renderer().ordinal());
            out.writeByte(config.resolution().ordinal());
            out.writeUTF(config.worldId());
            out.writeUTF(config.dimensionId());
            var worlds = Bukkit.getWorlds().stream().limit(32).toList();
            out.writeByte(worlds.size());
            for (World world : worlds) {
                out.writeUTF(MapTileEngine.worldId(world));
                out.writeUTF(MapTileEngine.dimensionId(world));
                String label = world.getName() + " · " + world.getEnvironment().name();
                out.writeUTF(label.length() > 64 ? label.substring(0, 64) : label);
                var worldConfig = MapTileEngine.configFor(world);
                out.writeByte(worldConfig.renderer().ordinal());
                out.writeByte(worldConfig.resolution().ordinal());
            }
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not encode map settings: " + exception.getMessage());
        }
    }

}
