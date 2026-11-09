package dev.ledge.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class LedgeFabricClient implements ClientModInitializer {
    private static final int FRAGMENT_BYTES = 24_000;
    private static final int MAX_TILE_BYTES = 1_000_000;
    private static final int MAX_TRANSFERS = 16;
    private static final int CACHE_LIMIT = 128;
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("ledge", "controls"));
    private static final KeyMapping OPEN_MAP = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.ledge.open_map", InputConstants.Type.KEYSYM, InputConstants.KEY_Z, CATEGORY));
    private static final LinkedHashMap<Integer, Transfer> TRANSFERS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Identifier> TEXTURES = new LinkedHashMap<>(16, 0.75f, true);
    private static final LinkedHashMap<String, ProjectedTexture> PROJECTED_TEXTURES = new LinkedHashMap<>(16, 0.75f, true);
    private static final Set<String> REQUESTED_TILES = ConcurrentHashMap.newKeySet();
    private static boolean showPlayerMarker = true;
    private static volatile int serverRenderer;
    private static volatile int serverResolution = 1;
    private static volatile int settingsVersion;
    private static volatile List<MapTarget> mapTargets = List.of();
    private static volatile MapTarget serverTarget;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.clientboundPlay().register(MapPayload.TYPE, MapPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MapPayload.TYPE, MapPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(MapPayload.TYPE, (payload, context) -> receive(payload.bytes()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_MAP.consumeClick()) {
                if (client.player != null && client.level != null) {
                    client.setScreenAndShow(new LedgeMapScreen());
                }
            }
        });
    }

    static void requestTile(String worldId, String dimension, int renderer, int resolution, int tileX, int tileZ, int zoom) {
        if (!ClientPlayNetworking.canSend(MapPayload.TYPE)) return;
        String requestKey = worldId + ":" + dimension + ":" + tileX + ":" + tileZ + ":" + zoom + ":" + renderer + ":" + resolution;
        if (!REQUESTED_TILES.add(requestKey)) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeByte(renderer);
            out.writeByte(resolution);
            out.writeByte(zoom);
            out.writeInt(tileX);
            out.writeInt(tileZ);
            out.writeUTF(worldId);
            out.writeUTF(dimension);
            ClientPlayNetworking.send(new MapPayload(bytes.toByteArray()));
        } catch (IOException ignored) {
            REQUESTED_TILES.remove(requestKey);
        }
    }

    /** Drops every cached tile so the next request downloads fresh images. */
    static void forgetTiles() {
        for (Identifier id : TEXTURES.values()) Minecraft.getInstance().getTextureManager().release(id);
        for (ProjectedTexture texture : PROJECTED_TEXTURES.values()) Minecraft.getInstance().getTextureManager().release(texture.texture());
        TEXTURES.clear();
        PROJECTED_TEXTURES.clear();
        TILE_PNGS.clear();
        REQUESTED_TILES.clear();
    }

    static void unsubscribeTiles() {
        if (ClientPlayNetworking.canSend(MapPayload.TYPE)) ClientPlayNetworking.send(new MapPayload(new byte[]{3}));
        REQUESTED_TILES.clear();
    }

    static void requestSettings() {
        if (ClientPlayNetworking.canSend(MapPayload.TYPE)) ClientPlayNetworking.send(new MapPayload(new byte[]{4}));
    }

    /** Raw PNG bytes of received 2D tiles, kept alongside the textures so prints contain only map pixels. */
    private static final java.util.Map<String, byte[]> TILE_PNGS = new java.util.concurrent.ConcurrentHashMap<>();

    static byte[] tilePng(String worldId, String dimension, int renderer, int resolution, int tileX, int tileZ, int zoom) {
        return TILE_PNGS.get(worldId + ":" + dimension + ":" + tileX + ":" + tileZ + ":" + zoom + ":" + renderer + ":" + resolution);
    }

    static Identifier textureFor(String worldId, String dimension, int renderer, int resolution, int tileX, int tileZ, int zoom) {
        return TEXTURES.get(worldId + ":" + dimension + ":" + tileX + ":" + tileZ + ":" + zoom + ":" + renderer + ":" + resolution);
    }

    static ProjectedTexture projectedTextureFor(String worldId, String dimension, int renderer, int resolution, int tileX, int tileZ, int zoom) {
        return PROJECTED_TEXTURES.get(worldId + ":" + dimension + ":" + tileX + ":" + tileZ + ":" + zoom + ":" + renderer + ":" + resolution);
    }

    static List<MapTarget> mapTargets() { return mapTargets; }
    static MapTarget serverTarget() { return serverTarget; }

    static void selectTarget(MapTarget target) {
        serverRenderer = target.renderer();
        serverResolution = target.resolution();
    }

    static boolean showPlayerMarker() {
        return showPlayerMarker;
    }

    static void togglePlayerMarker() {
        showPlayerMarker = !showPlayerMarker;
    }

    static int blocksPerTile(int zoom) {
        return (256 / (2 << serverResolution)) << zoom;
    }

    static int settingsVersion() {
        return settingsVersion;
    }

    private static void receive(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            int operation = in.readUnsignedByte();
            if (operation == 4) {
                int renderer = in.readUnsignedByte();
                int resolution = in.readUnsignedByte();
                String worldId = in.readUTF();
                String dimension = in.readUTF();
                int count = in.readUnsignedByte();
                if (renderer < 3 && resolution < 4 && worldId.length() <= 96 && dimension.length() <= 96 && count <= 32) {
                    java.util.ArrayList<MapTarget> targets = new java.util.ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        String targetWorldId = in.readUTF();
                        String targetDimension = in.readUTF();
                        String label = in.readUTF();
                        int targetRenderer = in.readUnsignedByte();
                        int targetResolution = in.readUnsignedByte();
                        if (targetWorldId.length() > 96 || targetDimension.length() > 96 || label.length() > 256
                                || targetRenderer >= 3 || targetResolution >= 4) return;
                        targets.add(new MapTarget(targetWorldId, targetDimension, label, targetRenderer, targetResolution));
                    }
                    if (in.available() != 0) return;
                    serverRenderer = renderer;
                    serverResolution = resolution;
                    mapTargets = List.copyOf(targets);
                    serverTarget = targets.stream().filter(target -> target.worldId().equals(worldId)
                            && target.dimension().equals(dimension)).findFirst().orElse(null);
                    settingsVersion++;
                }
                return;
            }
            if (operation != 2 && operation != 7) return;
            boolean projected = operation == 7;
            int renderer = in.readUnsignedByte();
            int resolution = in.readUnsignedByte();
            int zoom = in.readUnsignedByte();
            int tileX = in.readInt();
            int tileZ = in.readInt();
            String worldId = in.readUTF();
            String dimension = in.readUTF();
            int topOffset = projected ? in.readInt() : 0;
            int transferId = in.readInt();
            int part = in.readUnsignedShort();
            int partCount = in.readUnsignedShort();
            int totalBytes = in.readInt();
            int fragmentSize = in.available();
            if (renderer >= 3 || resolution >= 4 || zoom > 12 || worldId.length() > 96 || dimension.length() > 96
                    || projected && (topOffset < 0 || topOffset >= 4096)
                    || partCount < 1 || partCount > 48 || part >= partCount
                    || totalBytes < 1 || totalBytes > MAX_TILE_BYTES || fragmentSize > FRAGMENT_BYTES) return;
            Transfer transfer = TRANSFERS.computeIfAbsent(transferId,
                    ignored -> new Transfer(worldId, dimension, tileX, tileZ, renderer, resolution, zoom, projected, topOffset, partCount, totalBytes));
            if (!transfer.matches(worldId, dimension, tileX, tileZ, renderer, resolution, zoom, projected, topOffset, partCount, totalBytes) || transfer.parts[part] != null) return;
            byte[] fragment = in.readNBytes(fragmentSize);
            transfer.parts[part] = fragment;
            transfer.received += fragment.length;
            transfer.receivedParts++;
            if (transfer.receivedParts == partCount) {
                TRANSFERS.remove(transferId);
                if (transfer.received != totalBytes) return;
                ByteArrayOutputStream png = new ByteArrayOutputStream(totalBytes);
                for (byte[] value : transfer.parts) png.write(value);
                if (projected) installProjectedTile(worldId, dimension, tileX, tileZ, renderer, resolution, zoom, topOffset, png.toByteArray());
                else installTile(worldId, dimension, tileX, tileZ, renderer, resolution, zoom, png.toByteArray());
            }
            while (TRANSFERS.size() > MAX_TRANSFERS) TRANSFERS.remove(TRANSFERS.keySet().iterator().next());
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void installTile(String worldId, String dimension, int tileX, int tileZ, int renderer, int resolution, int zoom, byte[] png) {
        String key = worldId + ":" + dimension + ":" + tileX + ":" + tileZ + ":" + zoom + ":" + renderer + ":" + resolution;
        REQUESTED_TILES.remove(key);
        Identifier id = Identifier.fromNamespaceAndPath("ledge", "tiles/" + pathSegment(worldId) + "/"
                + pathSegment(dimension) + "/r" + resolution + "/m" + renderer + "/z" + zoom + "/"
                + coordinatePath(tileX) + "_" + coordinatePath(tileZ));
        try {
            NativeImage image = NativeImage.read(png);
            if (image.getWidth() != 256 || image.getHeight() != 256) {
                image.close();
                return;
            }
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Ledge map tile", image));
            TEXTURES.put(key, id);
            TILE_PNGS.put(key, png);
            while (TEXTURES.size() > CACHE_LIMIT) {
                String evictedKey = TEXTURES.keySet().iterator().next();
                TILE_PNGS.remove(evictedKey);
                Identifier evicted = TEXTURES.remove(evictedKey);
                Minecraft.getInstance().getTextureManager().release(evicted);
            }
        } catch (IOException ignored) {
        }
    }

    private static void installProjectedTile(String worldId, String dimension, int tileX, int tileZ, int renderer, int resolution,
                                             int zoom, int topOffset, byte[] png) {
        String key = worldId + ":" + dimension + ":" + tileX + ":" + tileZ + ":" + zoom + ":" + renderer + ":" + resolution;
        Identifier id = Identifier.fromNamespaceAndPath("ledge", "projected/" + pathSegment(worldId) + "/" + pathSegment(dimension) + "/r" + resolution
                + "/m" + renderer + "/z" + zoom + "/" + coordinatePath(tileX) + "_" + coordinatePath(tileZ));
        try {
            NativeImage image = NativeImage.read(png);
            if (image.getWidth() != 256 || image.getHeight() < 1 || image.getHeight() > 4096
                    || topOffset < 0 || topOffset >= image.getHeight()) {
                image.close();
                return;
            }
            int imageHeight = image.getHeight();
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Ledge projected tile", image));
            PROJECTED_TEXTURES.put(key, new ProjectedTexture(id, topOffset, imageHeight));
            while (PROJECTED_TEXTURES.size() > CACHE_LIMIT) {
                ProjectedTexture evicted = PROJECTED_TEXTURES.remove(PROJECTED_TEXTURES.keySet().iterator().next());
                Minecraft.getInstance().getTextureManager().release(evicted.texture());
            }
        } catch (IOException ignored) {
        }
    }

    private static String coordinatePath(int coordinate) {
        return coordinate < 0 ? "n" + -(long) coordinate : "p" + coordinate;
    }

    private static String pathSegment(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Transfer {
        private final String worldId;
        private final int tileX;
        private final int tileZ;
        private final int renderer;
        private final int resolution;
        private final int zoom;
        private final boolean projected;
        private final int topOffset;
        private final String dimension;
        private final int totalBytes;
        private final byte[][] parts;
        private int received;
        private int receivedParts;

        private Transfer(String worldId, String dimension, int tileX, int tileZ, int renderer, int resolution, int zoom,
                         boolean projected, int topOffset, int partCount, int totalBytes) {
            this.worldId = worldId;
            this.dimension = dimension;
            this.tileX = tileX;
            this.tileZ = tileZ;
            this.renderer = renderer;
            this.resolution = resolution;
            this.zoom = zoom;
            this.projected = projected;
            this.topOffset = topOffset;
            this.totalBytes = totalBytes;
            this.parts = new byte[partCount][];
        }

        private boolean matches(String worldId, String dimension, int x, int z, int renderer, int resolution, int zoom,
                                boolean projected, int topOffset, int count, int length) {
            return this.worldId.equals(worldId) && this.dimension.equals(dimension) && tileX == x && tileZ == z && this.renderer == renderer
                    && this.resolution == resolution && this.zoom == zoom
                    && this.projected == projected && this.topOffset == topOffset
                    && parts.length == count && totalBytes == length;
        }
    }

    record ProjectedTexture(Identifier texture, int topOffset, int height) {}
    record MapTarget(String worldId, String dimension, String label, int renderer, int resolution) {}
}
