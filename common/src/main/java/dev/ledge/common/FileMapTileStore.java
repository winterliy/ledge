package dev.ledge.common;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/** Atomic filesystem-backed PNG tile store shared by server and client adapters. */
public final class FileMapTileStore implements MapTileStore {
    private final Path root;

    public FileMapTileStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Optional<BufferedImage> read(MapTileKey key) throws IOException {
        Path file = tilePath(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null || image.getWidth() != MapTileKey.TILE_SIZE || image.getHeight() != MapTileKey.TILE_SIZE) {
            return Optional.empty();
        }
        return Optional.of(image);
    }

    @Override
    public void write(MapTileKey key, BufferedImage image) throws IOException {
        if (image == null || image.getWidth() != MapTileKey.TILE_SIZE || image.getHeight() != MapTileKey.TILE_SIZE) {
            throw new IllegalArgumentException("map tiles must be exactly 256x256 pixels");
        }
        Path file = tilePath(key);
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, FastPng.encode(image));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Optional<MapHeightTile> readHeights(MapTileKey key) throws IOException {
        Path file = heightPath(key);
        if (!Files.isRegularFile(file) || Files.size(file) != MapHeightTile.BYTE_SIZE) return Optional.empty();
        try {
            return Optional.of(MapHeightTile.fromBytes(Files.readAllBytes(file)));
        } catch (IllegalArgumentException | IOException exception) {
            return Optional.empty();
        }
    }

    public void writeHeights(MapTileKey key, MapHeightTile heights) throws IOException {
        Path file = heightPath(key);
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, heights.toBytes());
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Optional<MapProjectedTile> readProjected(MapTileKey key) throws IOException {
        Path file = projectedPath(key);
        if (!Files.isRegularFile(file) || Files.size(file) > MapProjectedTile.MAX_BYTES) return Optional.empty();
        try {
            return Optional.of(MapProjectedTile.fromBytes(Files.readAllBytes(file)));
        } catch (IllegalArgumentException | IOException exception) {
            return Optional.empty();
        }
    }

    public void writeProjected(MapTileKey key, MapProjectedTile projected) throws IOException {
        byte[] bytes = projected.toBytes();
        Path file = projectedPath(key);
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void delete(MapTileKey key) throws IOException {
        Files.deleteIfExists(tilePath(key));
        Files.deleteIfExists(heightPath(key));
        Files.deleteIfExists(projectedPath(key));
    }

    public Path tilePath(MapTileKey key) {
        Path path = root.resolve(key.worldId())
                .resolve(key.dimensionId())
                .resolve(key.renderer().name().toLowerCase(java.util.Locale.ROOT))
                .resolve("v5")
                .resolve(key.resolution().name().toLowerCase(java.util.Locale.ROOT))
                .resolve("z" + key.zoom())
                .resolve(key.tileX() + "_" + key.tileZ() + ".png")
                .normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("tile path escaped storage root");
        }
        return path;
    }

    private Path heightPath(MapTileKey key) {
        Path tile = tilePath(key);
        Path path = tile.resolveSibling(tile.getFileName() + ".height").normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("height tile path escaped storage root");
        return path;
    }

    private Path projectedPath(MapTileKey key) {
        Path tile = tilePath(key);
        Path path = tile.resolveSibling(tile.getFileName() + ".projected").normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("projected tile path escaped storage root");
        return path;
    }
}
