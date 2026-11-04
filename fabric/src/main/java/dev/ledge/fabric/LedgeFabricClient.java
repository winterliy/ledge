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
import java.util.LinkedHashMap;
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
            new KeyMapping("key.ledge.open_map", InputConstants.Type.KEYBOARD, InputConstants.KEY_Z, CATEGORY));
    private static final LinkedHashMap<Integer, Transfer> TRANSFERS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Identifier> TEXTURES = new LinkedHashMap<>(16, 0.75f, true);
    private static final Set<String> REQUESTED_TILES = ConcurrentHashMap.newKeySet();
    private static boolean showPlayerMarker = true;
    private static volatile int serverRenderer;
    private static volatile int serverResolution = 1;
    private static volatile int settingsVersion;

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

    static void requestTile(int tileX, int tileZ, int zoom) {
        if (!ClientPlayNetworking.canSend(MapPayload.TYPE)) return;
        String requestKey = currentDimension() + ":" + tileX + ":" + tileZ + ":" + zoom + ":" + serverRenderer + ":" + serverResolution;
        if (!REQUESTED_TILES.add(requestKey)) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(12);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeByte(0); // SURFACE
            out.writeByte(1); // STANDARD
            out.writeByte(zoom);
            out.writeInt(tileX);
            out.writeInt(tileZ);
            ClientPlayNetworking.send(new MapPayload(bytes.toByteArray()));
        } catch (IOException ignored) {
            REQUESTED_TILES.remove(requestKey);
        }
    }

    static void unsubscribeTiles() {
        if (ClientPlayNetworking.canSend(MapPayload.TYPE)) ClientPlayNetworking.send(new MapPayload(new byte[]{3}));
        REQUESTED_TILES.clear();
    }

    static void requestSettings() {
        if (ClientPlayNetworking.canSend(MapPayload.TYPE)) ClientPlayNetworking.send(new MapPayload(new byte[]{4}));
    }

    static Identifier textureFor(String dimension, int tileX, int tileZ, int zoom) {
        return TEXTURES.get(dimension + ":" + tileX + ":" + tileZ + ":" + zoom + ":" + serverRenderer + ":" + serverResolution);
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

    private static String currentDimension() {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? "unknown" : client.level.dimension().identifier().toString().replace(':', '_');
    }

    private static void receive(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            int operation = in.readUnsignedByte();
            if (operation == 4) {
                int renderer = in.readUnsignedByte();
                int resolution = in.readUnsignedByte();
                String dimension = in.readUTF();
                if (renderer < 3 && resolution < 4 && dimension.length() <= 96) {
                    serverRenderer = renderer;
                    serverResolution = resolution;
                    settingsVersion++;
                }
                return;
            }
            if (operation != 2) return;
            int renderer = in.readUnsignedByte();
            int resolution = in.readUnsignedByte();
            int zoom = in.readUnsignedByte();
            int tileX = in.readInt();
            int tileZ = in.readInt();
            String dimension = in.readUTF();
            int transferId = in.readInt();
            int part = in.readUnsignedShort();
            int partCount = in.readUnsignedShort();
            int totalBytes = in.readInt();
            int fragmentSize = in.available();
            if (renderer >= 3 || resolution >= 4 || zoom > 12 || dimension.length() > 96
                    || partCount < 1 || partCount > 48 || part >= partCount
                    || totalBytes < 1 || totalBytes > MAX_TILE_BYTES || fragmentSize > FRAGMENT_BYTES) return;
            if (serverRenderer != renderer || serverResolution != resolution) {
                serverRenderer = renderer;
                serverResolution = resolution;
                settingsVersion++;
            }
            Transfer transfer = TRANSFERS.computeIfAbsent(transferId,
                    ignored -> new Transfer(dimension, tileX, tileZ, renderer, resolution, zoom, partCount, totalBytes));
            if (!transfer.matches(dimension, tileX, tileZ, renderer, resolution, zoom, partCount, totalBytes) || transfer.parts[part] != null) return;
            byte[] fragment = in.readNBytes(fragmentSize);
            transfer.parts[part] = fragment;
            transfer.received += fragment.length;
            transfer.receivedParts++;
            if (transfer.receivedParts == partCount) {
                TRANSFERS.remove(transferId);
                if (transfer.received != totalBytes) return;
                ByteArrayOutputStream png = new ByteArrayOutputStream(totalBytes);
                for (byte[] value : transfer.parts) png.write(value);
                installTile(dimension, tileX, tileZ, renderer, resolution, zoom, png.toByteArray());
            }
            while (TRANSFERS.size() > MAX_TRANSFERS) TRANSFERS.remove(TRANSFERS.keySet().iterator().next());
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void installTile(String dimension, int tileX, int tileZ, int renderer, int resolution, int zoom, byte[] png) {
        String key = dimension + ":" + tileX + ":" + tileZ + ":" + zoom + ":" + renderer + ":" + resolution;
        REQUESTED_TILES.remove(key);
        Identifier id = Identifier.fromNamespaceAndPath("ledge", "tiles/" + dimension + "/r" + resolution + "/m" + renderer + "/z" + zoom + "/" + tileX + "_" + tileZ);
        try {
            NativeImage image = NativeImage.read(png);
            if (image.getWidth() != 256 || image.getHeight() != 256) {
                image.close();
                return;
            }
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Ledge map tile", image));
            TEXTURES.put(key, id);
            while (TEXTURES.size() > CACHE_LIMIT) {
                Identifier evicted = TEXTURES.remove(TEXTURES.keySet().iterator().next());
                Minecraft.getInstance().getTextureManager().release(evicted);
            }
        } catch (IOException ignored) {
        }
    }

    private static final class Transfer {
        private final int tileX;
        private final int tileZ;
        private final int renderer;
        private final int resolution;
        private final int zoom;
        private final String dimension;
        private final int totalBytes;
        private final byte[][] parts;
        private int received;
        private int receivedParts;

        private Transfer(String dimension, int tileX, int tileZ, int renderer, int resolution, int zoom, int partCount, int totalBytes) {
            this.dimension = dimension;
            this.tileX = tileX;
            this.tileZ = tileZ;
            this.renderer = renderer;
            this.resolution = resolution;
            this.zoom = zoom;
            this.totalBytes = totalBytes;
            this.parts = new byte[partCount][];
        }

        private boolean matches(String dimension, int x, int z, int renderer, int resolution, int zoom, int count, int length) {
            return this.dimension.equals(dimension) && tileX == x && tileZ == z && this.renderer == renderer
                    && this.resolution == resolution && this.zoom == zoom
                    && parts.length == count && totalBytes == length;
        }
    }
}
