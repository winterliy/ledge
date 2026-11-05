package dev.ledge.common;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/** A projected surface tile and the local pixel offset of its height-64 plane origin. */
public record MapProjectedTile(BufferedImage image, int topOffset) {
    private static final int MAGIC = 0x4c444750;
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = 20;
    public static final int MAX_HEIGHT = 4096;
    public static final int MAX_BYTES = 1_000_000;

    public MapProjectedTile {
        Objects.requireNonNull(image, "image");
        if (image.getWidth() != MapTileKey.TILE_SIZE || image.getHeight() < 1 || image.getHeight() > MAX_HEIGHT
                || topOffset < 0 || topOffset >= image.getHeight()) {
            throw new IllegalArgumentException("invalid projected tile bounds");
        }
    }

    public byte[] toBytes() throws IOException {
        byte[] pixels = toPng();
        if (pixels.length > MAX_BYTES - HEADER_SIZE) throw new IOException("projected tile is too large");
        ByteArrayOutputStream output = new ByteArrayOutputStream(HEADER_SIZE + pixels.length);
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.writeInt(MAGIC);
            data.writeInt(VERSION);
            data.writeInt(image.getWidth());
            data.writeInt(image.getHeight());
            data.writeInt(topOffset);
            data.write(pixels);
        }
        return output.toByteArray();
    }

    public byte[] toPng() throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream(16_000);
        if (!ImageIO.write(image, "png", png)) throw new IOException("PNG writer is not available");
        return png.toByteArray();
    }

    public static MapProjectedTile fromBytes(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < HEADER_SIZE + 8 || bytes.length > MAX_BYTES) throw new IOException("invalid projected tile length");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("unsupported projected tile format");
            int width = input.readInt();
            int height = input.readInt();
            int topOffset = input.readInt();
            if (width != MapTileKey.TILE_SIZE || height < 1 || height > MAX_HEIGHT || topOffset < 0 || topOffset >= height) {
                throw new IOException("invalid projected tile bounds");
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null || image.getWidth() != width || image.getHeight() != height) {
                throw new IOException("invalid projected tile image");
            }
            return new MapProjectedTile(image, topOffset);
        }
    }
}
