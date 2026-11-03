package dev.ledge.common;

import java.awt.image.BufferedImage;

/** Converts one bounded square of immutable block samples into a map tile. */
@FunctionalInterface
public interface MapTileRenderer {
    BufferedImage render(MapBlockSample[] samples, MapResolution resolution);
}
