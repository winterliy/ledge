package dev.ledge.common;

import java.awt.Polygon;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Objects;

/** Creates a shared isometric surface image with height-displaced tops and exposed cliff faces. */
public final class MapTileProjector {
    private static final int HEIGHT_REFERENCE = 64;

    private MapTileProjector() {
    }

    public static MapProjectedTile project(BufferedImage colors, MapHeightTile heights, MapBlockSample[] borderSamples,
                                           MapResolution resolution, int zoom) {
        Objects.requireNonNull(colors, "colors");
        Objects.requireNonNull(heights, "heights");
        Objects.requireNonNull(resolution, "resolution");
        if (colors.getWidth() != MapTileKey.TILE_SIZE || colors.getHeight() != MapTileKey.TILE_SIZE
                || zoom < MapTileKey.MIN_ZOOM || zoom > MapTileKey.MAX_ZOOM) {
            throw new IllegalArgumentException("invalid surface projection input");
        }
        int step = resolution.pixelsPerBlock();
        int samples = MapTileKey.TILE_SIZE / step;
        int borderSide = samples + 2;
        if (borderSamples == null || borderSamples.length != borderSide * borderSide) {
            throw new IllegalArgumentException("surface projection requires a one-sample border");
        }
        double verticalScale = step / (4.0 * (1 << zoom));
        int minHeight = Short.MAX_VALUE;
        int maxHeight = Short.MIN_VALUE;
        for (MapBlockSample sample : borderSamples) {
            minHeight = Math.min(minHeight, sample.height());
            maxHeight = Math.max(maxHeight, sample.height());
        }
        int topOffset = Math.max(0, (int) Math.ceil((maxHeight - HEIGHT_REFERENCE) * verticalScale));
        int bottomOffset = Math.max(0, (int) Math.ceil((HEIGHT_REFERENCE - minHeight) * verticalScale));
        int imageHeight = Math.min(MapProjectedTile.MAX_HEIGHT,
                MapTileKey.TILE_SIZE / 2 + topOffset + bottomOffset + 2);
        BufferedImage projected = new BufferedImage(MapTileKey.TILE_SIZE, imageHeight, BufferedImage.TYPE_INT_ARGB);
        int[] source = colors.getRGB(0, 0, MapTileKey.TILE_SIZE, MapTileKey.TILE_SIZE, null, 0, MapTileKey.TILE_SIZE);
        Graphics2D graphics = projected.createGraphics();
        try {
            for (int depth = 0; depth < samples * 2 - 1; depth++) {
                int minZ = Math.max(0, depth - samples + 1);
                int maxZ = Math.min(samples - 1, depth);
                for (int z = minZ; z <= maxZ; z++) {
                    int x = depth - z;
                    int height = heightAt(heights, step, x, z);
                    int color = source[(z * step + step / 2) * MapTileKey.TILE_SIZE + x * step + step / 2];
                    if ((color >>> 24) == 0) continue;
                    int u = x * step;
                    int v = z * step;
                    int[] a = point(u, v, height, maxHeight, topOffset, verticalScale);
                    int[] b = point(u + step, v, height, maxHeight, topOffset, verticalScale);
                    int[] c = point(u + step, v + step, height, maxHeight, topOffset, verticalScale);
                    int[] d = point(u, v + step, height, maxHeight, topOffset, verticalScale);
                    if (x + 1 < samples) {
                        int east = heightAt(heights, step, x + 1, z);
                        if (height != east) fill(graphics,
                                b, c,
                                point(u + step, v + step, east, maxHeight, topOffset, verticalScale),
                                point(u + step, v, east, maxHeight, topOffset, verticalScale), shade(color, 0.58));
                    } else {
                        int east = borderSamples[(z + 1) * borderSide + samples + 1].height();
                        if (height != east) fill(graphics,
                                b, c,
                                point(u + step, v + step, east, maxHeight, topOffset, verticalScale),
                                point(u + step, v, east, maxHeight, topOffset, verticalScale), shade(color, 0.58));
                    }
                    if (z + 1 < samples) {
                        int south = heightAt(heights, step, x, z + 1);
                        if (height != south) fill(graphics,
                                d, c,
                                point(u + step, v + step, south, maxHeight, topOffset, verticalScale),
                                point(u, v + step, south, maxHeight, topOffset, verticalScale), shade(color, 0.42));
                    } else {
                        int south = borderSamples[(samples + 1) * borderSide + x + 1].height();
                        if (height != south) fill(graphics,
                                d, c,
                                point(u + step, v + step, south, maxHeight, topOffset, verticalScale),
                                point(u, v + step, south, maxHeight, topOffset, verticalScale), shade(color, 0.42));
                    }
                    fill(graphics, a, b, c, d, color);
                }
            }
        } finally {
            graphics.dispose();
        }
        return new MapProjectedTile(projected, topOffset);
    }

    private static int heightAt(MapHeightTile heights, int step, int sampleX, int sampleZ) {
        return heights.heightAt(sampleX * step + step / 2, sampleZ * step + step / 2);
    }

    private static int[] point(int u, int v, int height, int maxHeight, int topOffset, double verticalScale) {
        int x = MapTileKey.TILE_SIZE / 2 + (u - v) / 2;
        int y = (u + v) / 4 + topOffset + (int) Math.round((maxHeight - height) * verticalScale);
        return new int[]{x, y};
    }

    private static void fill(Graphics2D graphics, int[] a, int[] b, int[] c, int[] d, int color) {
        Polygon polygon = new Polygon(new int[]{a[0], b[0], c[0], d[0]},
                new int[]{a[1], b[1], c[1], d[1]}, 4);
        graphics.setColor(new java.awt.Color(color, true));
        graphics.fillPolygon(polygon);
    }

    private static int shade(int color, double factor) {
        int alpha = color >>> 24;
        int red = (int) (((color >>> 16) & 0xff) * factor);
        int green = (int) (((color >>> 8) & 0xff) * factor);
        int blue = (int) ((color & 0xff) * factor);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
}
