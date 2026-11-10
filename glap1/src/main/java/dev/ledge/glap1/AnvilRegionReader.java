package dev.ledge.glap1;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads saved chunks straight from Anvil region files ({@code r.X.Z.mca}) without loading them into the server,
 * so map tiles for unloaded areas cost no server-thread time. One instance belongs to one region worker and keeps
 * a few region files open; it is not thread-safe.
 */
final class AnvilRegionReader implements AutoCloseable {
    private static final int OPEN_REGIONS = 8;
    /** Chunk statuses that mean "fully generated" before 1.18 (1.13 used fullchunk/postprocessed, 1.14+ full). */
    private static final java.util.Set<String> LEGACY_DONE = new java.util.HashSet<>(java.util.Arrays.asList("full", "fullchunk", "postprocessed"));

    private final Map<Path, RandomAccessFile> open = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, RandomAccessFile> eldest) {
            if (size() <= OPEN_REGIONS) return false;
            try { eldest.getValue().close(); } catch (IOException ignored) { }
            return true;
        }
    };

    /** Returns the chunk's columns, or null when the chunk was never generated or cannot be read. */
    ChunkColumns read(Path regionFolder, int chunkX, int chunkZ, int minY, int worldHeight) {
        Path file = regionFolder.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        try {
            RandomAccessFile region = open.get(file);
            if (region == null) {
                if (!Files.isRegularFile(file)) return null;
                region = new RandomAccessFile(file.toFile(), "r");
                open.put(file, region);
            }
            int index = (chunkX & 31) + (chunkZ & 31) * 32;
            region.seek(index * 4L);
            int location = region.readInt();
            int sectorOffset = location >>> 8;
            if (sectorOffset == 0) return null;
            region.seek(sectorOffset * 4096L);
            int length = region.readInt();
            int compression = region.readUnsignedByte();
            if (length <= 1 || length > 1 << 24 || (compression & 0x80) != 0) return null;
            byte[] data = new byte[length - 1];
            region.readFully(data);
            InputStream raw = new ByteArrayInputStream(data);
            InputStream in = switch (compression) {
                case 1 -> new GZIPInputStream(raw);
                case 2 -> new InflaterInputStream(raw);
                case 3 -> raw;
                default -> null;
            };
            if (in == null) return null;
            Object root = Nbt.readRoot(new DataInputStream(new BufferedInputStream(in)));
            if (!(root instanceof Map<?, ?> chunk)) return null;
            if (chunk.get("Level") instanceof Map<?, ?> level) {
                // Up to 1.17: everything lives under "Level" (Sections/Palette/BlockStates, or numeric Blocks before 1.13).
                Object status = level.get("Status");
                if (status != null && !LEGACY_DONE.contains(status.toString().replace("minecraft:", ""))) return null;
                int dataVersion = chunk.get("DataVersion") instanceof Number number ? number.intValue() : 0;
                return LegacyChunk.from(level, dataVersion, minY, worldHeight);
            }
            Object status = chunk.get("Status");
            if (status != null && !status.toString().endsWith("full")) return null;
            return ChunkColumns.from(chunk, minY, worldHeight);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    @Override
    public void close() {
        for (RandomAccessFile file : open.values()) {
            try { file.close(); } catch (IOException ignored) { }
        }
        open.clear();
    }

    /** Decoded surface data of one chunk. */
    /** One 16x16x16 section that can name the block and biome at a local position. */
    interface SectionData {
        String block(int x, int y, int z);
        String biome(int x, int y, int z);
    }

    static class ChunkColumns {
        final int minY;
        final int[] surface = new int[256];
        final Map<Integer, SectionData> sections = new HashMap<>();

        ChunkColumns(int minY) { this.minY = minY; }

        static ChunkColumns from(Map<?, ?> chunk, int minY, int worldHeight) {
            ChunkColumns columns = new ChunkColumns(minY);
            int bits = 32 - Integer.numberOfLeadingZeros(worldHeight);
            long[] heights = null;
            if (chunk.get("Heightmaps") instanceof Map<?, ?> maps && maps.get("WORLD_SURFACE") instanceof long[] values) heights = values;
            if (chunk.get("sections") instanceof List<?> list) {
                for (Object entry : list) {
                    if (!(entry instanceof Map<?, ?> section) || !(section.get("Y") instanceof Number y)) continue;
                    Section decoded = Section.from(section);
                    if (decoded != null) columns.sections.put(y.intValue(), decoded);
                }
            }
            for (int i = 0; i < 256; i++) {
                int value = heights == null ? 0 : packed(heights, i, bits, false);
                columns.surface[i] = value > 0 ? minY + value - 1 : columns.scanDown(i & 15, i >> 4, minY + worldHeight - 1);
            }
            return columns;
        }

        int scanDown(int x, int z, int fromY) {
            for (int y = fromY; y >= minY; y--) {
                String name = blockName(x, y, z);
                if (name != null && !isAir(name)) return y;
            }
            return minY;
        }

        static boolean isAir(String name) {
            return name.endsWith(":air") || name.endsWith("cave_air") || name.endsWith("void_air") || name.startsWith("legacy:0:");
        }

        int surfaceY(int localX, int localZ) { return surface[localZ * 16 + localX]; }

        String blockName(int localX, int y, int localZ) {
            SectionData section = sections.get(Math.floorDiv(y, 16));
            return section == null ? null : section.block(localX, y & 15, localZ);
        }

        String biomeName(int localX, int y, int localZ) {
            SectionData section = sections.get(Math.floorDiv(y, 16));
            return section == null ? null : section.biome(localX, y & 15, localZ);
        }
    }

    private static final class Section implements SectionData {
        private final List<String> blockPalette;
        private final long[] blockData;
        private final List<String> biomePalette;
        private final long[] biomeData;

        private Section(List<String> blockPalette, long[] blockData, List<String> biomePalette, long[] biomeData) {
            this.blockPalette = blockPalette;
            this.blockData = blockData;
            this.biomePalette = biomePalette;
            this.biomeData = biomeData;
        }

        static Section from(Map<?, ?> section) {
            List<String> blocks = new ArrayList<>();
            long[] blockData = null;
            if (section.get("block_states") instanceof Map<?, ?> states) {
                if (states.get("palette") instanceof List<?> palette) {
                    for (Object entry : palette) {
                        // Up to 1.21: {Name, Properties}. 26.x: {"": id} for plain blocks or {id, properties}.
                        blocks.add(paletteId(entry));
                    }
                }
                if (states.get("data") instanceof long[] data) blockData = data;
            }
            List<String> biomes = new ArrayList<>();
            long[] biomeData = null;
            if (section.get("biomes") instanceof Map<?, ?> biomeStates) {
                if (biomeStates.get("palette") instanceof List<?> palette) for (Object entry : palette) biomes.add(paletteId(entry));
                if (biomeStates.get("data") instanceof long[] data) biomeData = data;
            }
            if (blocks.isEmpty() && biomes.isEmpty()) return null;
            return new Section(blocks, blockData, biomes, biomeData);
        }

        private static String paletteId(Object entry) {
            if (entry instanceof String id) return id;
            if (entry instanceof Map<?, ?> state) {
                for (String key : new String[]{"Name", "id", ""}) {
                    if (state.get(key) instanceof String id) return id;
                }
            }
            return "minecraft:air";
        }

        @Override public String block(int x, int y, int z) {
            if (blockPalette.isEmpty()) return null;
            if (blockPalette.size() == 1 || blockData == null) return blockPalette.get(0);
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(blockPalette.size() - 1));
            int value = packed(blockData, (y * 16 + z) * 16 + x, bits, false);
            return value < blockPalette.size() ? blockPalette.get(value) : null;
        }

        @Override public String biome(int x, int y, int z) {
            if (biomePalette.isEmpty()) return null;
            if (biomePalette.size() == 1 || biomeData == null) return biomePalette.get(0);
            int bits = 32 - Integer.numberOfLeadingZeros(biomePalette.size() - 1);
            int value = packed(biomeData, ((y >> 2) * 4 + (z >> 2)) * 4 + (x >> 2), bits, false);
            return value < biomePalette.size() ? biomePalette.get(value) : null;
        }
    }

    /** Before 1.16 (data version 2527) entries span across longs; from 1.16 they are padded per long. */
    static int packed(long[] data, int index, int bits, boolean spanning) {
        if (spanning) {
            long bit = (long) index * bits;
            int longIndex = (int) (bit >>> 6);
            int offset = (int) (bit & 63);
            if (longIndex >= data.length) return 0;
            long value = data[longIndex] >>> offset;
            if (offset + bits > 64 && longIndex + 1 < data.length) value |= data[longIndex + 1] << (64 - offset);
            return (int) (value & ((1L << bits) - 1));
        }
        int perLong = 64 / bits;
        int longIndex = index / perLong;
        if (longIndex >= data.length) return 0;
        int offset = (index % perLong) * bits;
        return (int) ((data[longIndex] >>> offset) & ((1L << bits) - 1));
    }

    /** Minimal NBT reader returning Map / List / boxed values / arrays. */
    private static final class Nbt {
        static Object readRoot(DataInputStream in) throws IOException {
            int type = in.readUnsignedByte();
            if (type != 10) return null;
            in.readUTF();
            return read(in, type, 0);
        }

        private static Object read(DataInputStream in, int type, int depth) throws IOException {
            if (depth > 64) throw new IOException("NBT too deep");
            return switch (type) {
                case 1 -> in.readByte();
                case 2 -> in.readShort();
                case 3 -> in.readInt();
                case 4 -> in.readLong();
                case 5 -> in.readFloat();
                case 6 -> in.readDouble();
                case 7 -> { byte[] value = new byte[checked(in.readInt())]; in.readFully(value); yield value; }
                case 8 -> in.readUTF();
                case 9 -> {
                    int elementType = in.readUnsignedByte();
                    int size = checked(in.readInt());
                    List<Object> list = new ArrayList<>(Math.min(size, 4096));
                    for (int i = 0; i < size; i++) list.add(read(in, elementType, depth + 1));
                    yield list;
                }
                case 10 -> {
                    Map<String, Object> map = new HashMap<>();
                    while (true) {
                        int childType = in.readUnsignedByte();
                        if (childType == 0) break;
                        String name = in.readUTF();
                        map.put(name, read(in, childType, depth + 1));
                    }
                    yield map;
                }
                case 11 -> { int[] value = new int[checked(in.readInt())]; for (int i = 0; i < value.length; i++) value[i] = in.readInt(); yield value; }
                case 12 -> { long[] value = new long[checked(in.readInt())]; for (int i = 0; i < value.length; i++) value[i] = in.readLong(); yield value; }
                case 0 -> null;
                default -> throw new IOException("Unknown NBT tag " + type);
            };
        }

        private static int checked(int size) throws IOException {
            if (size < 0 || size > 1 << 24) throw new IOException("Bad NBT length");
            return size;
        }
    }
}
