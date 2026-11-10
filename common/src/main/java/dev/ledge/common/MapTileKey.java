package dev.ledge.common;

import java.util.Objects;

/** Stable address of a bounded image tile. */
@com.github.bsideup.jabel.Desugar
public record MapTileKey(
        String worldId,
        String dimensionId,
        MapRendererType renderer,
        MapResolution resolution,
        int zoom,
        int tileX,
        int tileZ
) {
    public static final int TILE_SIZE = 256;
    public static final int MIN_ZOOM = 0;
    public static final int MAX_ZOOM = 12;

    public MapTileKey {
        worldId = requireId(worldId, "worldId");
        dimensionId = requireId(dimensionId, "dimensionId");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(resolution, "resolution");
        if (zoom < MIN_ZOOM || zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom must be between " + MIN_ZOOM + " and " + MAX_ZOOM);
        }
    }

    /** Converts block coordinates to tile coordinates, including negative positions. */
    public static MapTileKey atBlock(
            String worldId,
            String dimensionId,
            MapRendererType renderer,
            MapResolution resolution,
            int zoom,
            int blockX,
            int blockZ
    ) {
        int blocksPerTile = TILE_SIZE / resolution.pixelsPerBlock();
        return new MapTileKey(
                worldId,
                dimensionId,
                renderer,
                resolution,
                zoom,
                Math.floorDiv(blockX, blocksPerTile),
                Math.floorDiv(blockZ, blocksPerTile)
        );
    }

    public int blocksPerTile() {
        return Math.multiplyExact(TILE_SIZE / resolution.pixelsPerBlock(), 1 << zoom);
    }

    public int samplesPerSide() {
        return TILE_SIZE / resolution.pixelsPerBlock();
    }

    public int blocksPerSample() {
        return 1 << zoom;
    }

    public int minBlockX() {
        return Math.multiplyExact(tileX, blocksPerTile());
    }

    public int minBlockZ() {
        return Math.multiplyExact(tileZ, blocksPerTile());
    }

    private static String requireId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[a-zA-Z0-9_.-]{1,96}")) {
            throw new IllegalArgumentException(field + " must be a safe identifier");
        }
        return value;
    }
}
