package dev.ledge.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** A bounded 256x256 grid of signed world-height samples for a color tile. */
public final class MapHeightTile {
    private static final int MAGIC = 0x4c444748;
    private static final short VERSION = 1;
    private static final int HEADER_SIZE = 8;
    private static final int SAMPLE_COUNT = MapTileKey.TILE_SIZE * MapTileKey.TILE_SIZE;
    public static final int BYTE_SIZE = HEADER_SIZE + SAMPLE_COUNT * Short.BYTES;

    private final short[] heights;

    private MapHeightTile(short[] heights) {
        if (heights.length != SAMPLE_COUNT) {
            throw new IllegalArgumentException("height tile must contain exactly 256x256 samples");
        }
        this.heights = heights.clone();
    }

    public static MapHeightTile fromSurfaceSamples(MapBlockSample[] samples, MapResolution resolution) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(resolution, "resolution");
        int pixelsPerSample = resolution.pixelsPerBlock();
        int samplesPerSide = MapTileKey.TILE_SIZE / pixelsPerSample;
        int sampleSide = samplesPerSide + 2;
        if (samples.length != sampleSide * sampleSide) {
            throw new IllegalArgumentException("surface samples must include a one-sample border");
        }
        short[] heights = new short[SAMPLE_COUNT];
        for (int sampleZ = 0; sampleZ < samplesPerSide; sampleZ++) {
            for (int sampleX = 0; sampleX < samplesPerSide; sampleX++) {
                int height = samples[(sampleZ + 1) * sampleSide + sampleX + 1].height();
                if (height < Short.MIN_VALUE || height > Short.MAX_VALUE) {
                    throw new IllegalArgumentException("world height is outside the signed 16-bit range");
                }
                short value = (short) height;
                int startX = sampleX * pixelsPerSample;
                int startZ = sampleZ * pixelsPerSample;
                for (int y = startZ; y < startZ + pixelsPerSample; y++) {
                    int row = y * MapTileKey.TILE_SIZE;
                    for (int x = startX; x < startX + pixelsPerSample; x++) heights[row + x] = value;
                }
            }
        }
        return new MapHeightTile(heights);
    }

    public short heightAt(int x, int z) {
        if (x < 0 || x >= MapTileKey.TILE_SIZE || z < 0 || z >= MapTileKey.TILE_SIZE) {
            throw new IndexOutOfBoundsException("height coordinate must be within the tile");
        }
        return heights[z * MapTileKey.TILE_SIZE + x];
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(BYTE_SIZE).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC).putShort(VERSION).putShort((short) MapTileKey.TILE_SIZE);
        for (short height : heights) buffer.putShort(height);
        return buffer.array();
    }

    public static MapHeightTile fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != BYTE_SIZE) throw new IllegalArgumentException("invalid height tile length");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.getShort() != VERSION || buffer.getShort() != MapTileKey.TILE_SIZE) {
            throw new IllegalArgumentException("unsupported height tile format");
        }
        short[] heights = new short[SAMPLE_COUNT];
        for (int i = 0; i < heights.length; i++) heights[i] = buffer.getShort();
        return new MapHeightTile(heights);
    }
}
