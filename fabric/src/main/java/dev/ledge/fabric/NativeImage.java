package dev.ledge.fabric;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * The part of the newer NativeImage the client uses, over a BufferedImage. Pixels are exchanged as ABGR like
 * NativeImage before 1.21.2, so the shared screen code needs no changes.
 */
final class NativeImage implements AutoCloseable {
    final BufferedImage image;

    NativeImage(BufferedImage image) { this.image = image; }

    NativeImage(int width, int height, boolean ignored) { this(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)); }

    static NativeImage read(InputStream in) throws IOException {
        BufferedImage read = ImageIO.read(in);
        if (read == null) throw new IOException("Unsupported image");
        BufferedImage argb = new BufferedImage(read.getWidth(), read.getHeight(), BufferedImage.TYPE_INT_ARGB);
        argb.getGraphics().drawImage(read, 0, 0, null);
        return new NativeImage(argb);
    }

    int getWidth() { return image.getWidth(); }
    int getHeight() { return image.getHeight(); }

    private static int swap(int c) { return (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16); }

    int getPixelRGBA(int x, int y) { return swap(image.getRGB(x, y)); }
    void setPixelRGBA(int x, int y, int abgr) { image.setRGB(x, y, swap(abgr)); }

    void fillRect(int x, int y, int width, int height, int abgr) {
        int argb = swap(abgr);
        for (int py = y; py < y + height; py++) for (int px = x; px < x + width; px++) image.setRGB(px, py, argb);
    }

    void writeToFile(Path path) throws IOException { ImageIO.write(image, "png", path.toFile()); }

    @Override public void close() { }
}
