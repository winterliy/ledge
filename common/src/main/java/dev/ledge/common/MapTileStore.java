package dev.ledge.common;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Optional;

/** Persistent tile cache. Implementations must only accept bounded 256x256 images. */
public interface MapTileStore {
    Optional<BufferedImage> read(MapTileKey key) throws IOException;

    void write(MapTileKey key, BufferedImage image) throws IOException;

    void delete(MapTileKey key) throws IOException;
}
