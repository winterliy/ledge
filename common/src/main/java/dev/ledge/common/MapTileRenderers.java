package dev.ledge.common;

import java.awt.image.BufferedImage;
import java.util.Objects;

/** Built-in renderers kept free of Minecraft and platform APIs. */
public final class MapTileRenderers {
    private MapTileRenderers() {
    }

    public static MapTileRenderer forType(MapRendererType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case FLAT -> MapTileRenderers::flat;
            case SURFACE -> MapTileRenderers::surface;
            case CAVE -> MapTileRenderers::cave;
        };
    }

    private static BufferedImage flat(MapBlockSample[] samples, MapResolution resolution) {
        return compose(samples, resolution, (sample, index, width) -> sample.mapColorArgb());
    }

    private static BufferedImage surface(MapBlockSample[] samples, MapResolution resolution) {
        return compose(samples, resolution, (sample, index, width) -> {
            int row = index / width;
            int previousRow = Math.max(0, row - 1);
            int neighborIndex = previousRow * width + (index % width);
            int slope = sample.height() - samples[neighborIndex].height();
            int shade = Math.max(150, Math.min(255, 210 + slope * 6));
            return shade(sample.mapColorArgb(), shade);
        });
    }

    private static BufferedImage cave(MapBlockSample[] samples, MapResolution resolution) {
        return compose(samples, resolution, (sample, index, width) -> sample.caveExposed()
                ? shade(sample.mapColorArgb(), 210)
                : 0x00000000);
    }

    private static BufferedImage compose(MapBlockSample[] samples, MapResolution resolution, Colorizer colorizer) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(resolution, "resolution");
        int blocksPerSide = MapTileKey.TILE_SIZE / resolution.pixelsPerBlock();
        if (samples.length != blocksPerSide * blocksPerSide) {
            throw new IllegalArgumentException("sample count must match the selected resolution");
        }
        BufferedImage image = new BufferedImage(MapTileKey.TILE_SIZE, MapTileKey.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        int pixelsPerBlock = resolution.pixelsPerBlock();
        for (int blockZ = 0; blockZ < blocksPerSide; blockZ++) {
            for (int blockX = 0; blockX < blocksPerSide; blockX++) {
                int index = blockZ * blocksPerSide + blockX;
                int argb = colorizer.color(samples[index], index, blocksPerSide);
                int pixelX = blockX * pixelsPerBlock;
                int pixelZ = blockZ * pixelsPerBlock;
                for (int dz = 0; dz < pixelsPerBlock; dz++) {
                    for (int dx = 0; dx < pixelsPerBlock; dx++) {
                        image.setRGB(pixelX + dx, pixelZ + dz, argb);
                    }
                }
            }
        }
        return image;
    }

    private static int shade(int argb, int brightness) {
        int alpha = argb >>> 24;
        int red = ((argb >>> 16) & 0xff) * brightness / 255;
        int green = ((argb >>> 8) & 0xff) * brightness / 255;
        int blue = (argb & 0xff) * brightness / 255;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    @FunctionalInterface
    private interface Colorizer {
        int color(MapBlockSample sample, int index, int width);
    }
}
