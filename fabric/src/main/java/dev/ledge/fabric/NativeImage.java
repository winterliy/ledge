package dev.ledge.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Named facade over 1.13's NativeImage (class_4277 has no yarn names yet). Pixels are ABGR. */
final class NativeImage implements AutoCloseable {
    final net.minecraft.class_4277 image;

    NativeImage(net.minecraft.class_4277 image) { this.image = image; }
    NativeImage(int width, int height, boolean clear) { this(new net.minecraft.class_4277(width, height, clear)); }

    static NativeImage read(InputStream in) throws IOException { return new NativeImage(net.minecraft.class_4277.method_19472(in)); }

    int getWidth() { return image.method_19458(); }
    int getHeight() { return image.method_19478(); }
    int getPixelRGBA(int x, int y) { return image.method_19459(x, y); }
    void setPixelRGBA(int x, int y, int abgr) { image.method_19460(x, y, abgr); }

    void fillRect(int x, int y, int width, int height, int abgr) {
        for (int py = y; py < y + height; py++) for (int px = x; px < x + width; px++) setPixelRGBA(px, py, abgr);
    }

    void writeToFile(Path path) throws IOException {
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(getWidth(), getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < getHeight(); y++) for (int x = 0; x < getWidth(); x++) {
            int c = getPixelRGBA(x, y);
            out.setRGB(x, y, (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16));
        }
        javax.imageio.ImageIO.write(out, "png", path.toFile());
    }

    @Override public void close() { image.close(); }
}
