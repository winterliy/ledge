package dev.ledge.glap1;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decodes chunks saved before 1.18, where data sits under a {@code Level} compound:
 * <ul>
 *   <li>1.13 – 1.17: {@code Sections[].Palette} + {@code BlockStates} (entries span longs before data version 2527),
 *       {@code Heightmaps.WORLD_SURFACE}, numeric {@code Biomes} (256 columns until 1.14, 4x4x4 cells from 1.15);</li>
 *   <li>1.12 and older: numeric {@code Blocks}/{@code Add}/{@code Data} arrays and a {@code byte[256]} biome map.
 *       Blocks are reported as {@code legacy:<id>:<data>}.</li>
 * </ul>
 */
final class LegacyChunk {
    private LegacyChunk() { }

    static AnvilRegionReader.ChunkColumns from(Map<?, ?> level, int dataVersion, int minY, int worldHeight) {
        AnvilRegionReader.ChunkColumns columns = new AnvilRegionReader.ChunkColumns(minY);
        boolean spanning = dataVersion < 2527;
        Biomes biomes = Biomes.of(level.get("Biomes"));
        int topY = minY;
        if (level.get("Sections") instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> section) || !(section.get("Y") instanceof Number y)) continue;
                AnvilRegionReader.SectionData decoded = section(section, y.intValue() * 16, spanning, biomes);
                if (decoded == null) continue;
                columns.sections.put(y.intValue(), decoded);
                topY = Math.max(topY, y.intValue() * 16 + 15);
            }
        }
        long[] heights = null;
        if (level.get("Heightmaps") instanceof Map<?, ?> maps && maps.get("WORLD_SURFACE") instanceof long[] values) heights = values;
        int bits = 32 - Integer.numberOfLeadingZeros(worldHeight);
        for (int i = 0; i < 256; i++) {
            int value = heights == null ? 0 : AnvilRegionReader.packed(heights, i, bits, spanning);
            columns.surface[i] = value > 0 ? minY + value - 1 : columns.scanDown(i & 15, i >> 4, topY);
        }
        return columns;
    }

    private static AnvilRegionReader.SectionData section(Map<?, ?> section, int baseY, boolean spanning, Biomes biomes) {
        if (section.get("Palette") instanceof List<?> palette && section.get("BlockStates") instanceof long[] states) {
            List<String> names = new ArrayList<>();
            for (Object state : palette) names.add(state instanceof Map<?, ?> map && map.get("Name") instanceof String name ? name : "minecraft:air");
            if (names.isEmpty()) return null;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(names.size() - 1));
            return new AnvilRegionReader.SectionData() {
                @Override public String block(int x, int y, int z) {
                    if (names.size() == 1) return names.get(0);
                    int value = AnvilRegionReader.packed(states, (y * 16 + z) * 16 + x, bits, spanning);
                    return value < names.size() ? names.get(value) : null;
                }
                @Override public String biome(int x, int y, int z) { return biomes.at(x, baseY + y, z); }
            };
        }
        if (section.get("Blocks") instanceof byte[] blocks && blocks.length == 4096) {
            byte[] add = section.get("Add") instanceof byte[] a && a.length == 2048 ? a : null;
            byte[] data = section.get("Data") instanceof byte[] d && d.length == 2048 ? d : null;
            return new AnvilRegionReader.SectionData() {
                @Override public String block(int x, int y, int z) {
                    int index = (y * 16 + z) * 16 + x;
                    int id = blocks[index] & 0xFF;
                    if (add != null) id |= nibble(add, index) << 8;
                    return "legacy:" + id + ":" + (data == null ? 0 : nibble(data, index));
                }
                @Override public String biome(int x, int y, int z) { return biomes.at(x, baseY + y, z); }
            };
        }
        return null;
    }

    private static int nibble(byte[] array, int index) {
        int value = array[index >> 1];
        return (index & 1) == 0 ? value & 0x0F : (value >> 4) & 0x0F;
    }

    /** Numeric biome storage of pre-1.18 chunks. */
    private static final class Biomes {
        private final int[] ids;
        private final boolean cells;

        private Biomes(int[] ids, boolean cells) { this.ids = ids; this.cells = cells; }

        static Biomes of(Object raw) {
            if (raw instanceof int[] values) return new Biomes(values, values.length == 1024);
            if (raw instanceof byte[] values) {
                int[] ids = new int[values.length];
                for (int i = 0; i < values.length; i++) ids[i] = values[i] & 0xFF;
                return new Biomes(ids, false);
            }
            return new Biomes(new int[0], false);
        }

        String at(int x, int y, int z) {
            int index;
            if (cells) index = (Math.max(0, Math.min(63, y >> 2)) << 4) | ((z >> 2) << 2) | (x >> 2);
            else index = z * 16 + x;
            if (index >= ids.length) return null;
            return name(ids[index]);
        }
    }

    /** Pre-1.18 numeric biome ids (1.12 shares ids 0-39 and 127-167) as modern-style keys. */
    static String name(int id) {
        String name = switch (id) {
            case 0 -> "ocean"; case 1 -> "plains"; case 2 -> "desert"; case 3 -> "mountains"; case 4 -> "forest";
            case 5 -> "taiga"; case 6 -> "swamp"; case 7 -> "river"; case 8 -> "nether_wastes"; case 9 -> "the_end";
            case 10 -> "frozen_ocean"; case 11 -> "frozen_river"; case 12 -> "snowy_tundra"; case 13 -> "snowy_mountains";
            case 14 -> "mushroom_fields"; case 15 -> "mushroom_field_shore"; case 16 -> "beach"; case 17 -> "desert_hills";
            case 18 -> "wooded_hills"; case 19 -> "taiga_hills"; case 20 -> "mountain_edge"; case 21 -> "jungle";
            case 22 -> "jungle_hills"; case 23 -> "jungle_edge"; case 24 -> "deep_ocean"; case 25 -> "stone_shore";
            case 26 -> "snowy_beach"; case 27 -> "birch_forest"; case 28 -> "birch_forest_hills"; case 29 -> "dark_forest";
            case 30 -> "snowy_taiga"; case 31 -> "snowy_taiga_hills"; case 32 -> "giant_tree_taiga"; case 33 -> "giant_tree_taiga_hills";
            case 34 -> "wooded_mountains"; case 35 -> "savanna"; case 36 -> "savanna_plateau"; case 37 -> "badlands";
            case 38 -> "wooded_badlands_plateau"; case 39 -> "badlands_plateau"; case 40 -> "small_end_islands";
            case 41 -> "end_midlands"; case 42 -> "end_highlands"; case 43 -> "end_barrens"; case 44 -> "warm_ocean";
            case 45 -> "lukewarm_ocean"; case 46 -> "cold_ocean"; case 47 -> "deep_warm_ocean"; case 48 -> "deep_lukewarm_ocean";
            case 49 -> "deep_cold_ocean"; case 50 -> "deep_frozen_ocean"; case 127 -> "the_void"; case 129 -> "sunflower_plains";
            case 130 -> "desert_lakes"; case 131 -> "gravelly_mountains"; case 132 -> "flower_forest"; case 133 -> "taiga_mountains";
            case 134 -> "swamp_hills"; case 140 -> "ice_spikes"; case 149 -> "modified_jungle"; case 151 -> "modified_jungle_edge";
            case 155 -> "tall_birch_forest"; case 156 -> "tall_birch_hills"; case 157 -> "dark_forest_hills";
            case 158 -> "snowy_taiga_mountains"; case 160 -> "giant_spruce_taiga"; case 161 -> "giant_spruce_taiga_hills";
            case 162 -> "modified_gravelly_mountains"; case 163 -> "shattered_savanna"; case 164 -> "shattered_savanna_plateau";
            case 165 -> "eroded_badlands"; case 166 -> "modified_wooded_badlands_plateau"; case 167 -> "modified_badlands_plateau";
            case 168 -> "bamboo_jungle"; case 169 -> "bamboo_jungle_hills"; case 170 -> "soul_sand_valley";
            case 171 -> "crimson_forest"; case 172 -> "warped_forest"; case 173 -> "basalt_deltas";
            default -> "plains";
        };
        return "minecraft:" + name;
    }
}
