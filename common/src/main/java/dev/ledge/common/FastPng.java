package dev.ledge.common;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Small RGBA PNG encoder tuned for map tiles: no row filters and fastest deflate. Several times faster than
 * ImageIO for 256x256 tiles at a slightly larger file size.
 */
public final class FastPng {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private FastPng() { }

    public static byte[] encode(BufferedImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb;
        if (image.getType() == BufferedImage.TYPE_INT_ARGB && image.getRaster().getDataBuffer() instanceof DataBufferInt buffer
                && buffer.getNumBanks() == 1 && image.getRaster().getMinX() == 0 && image.getRaster().getMinY() == 0) {
            argb = buffer.getData();
        } else {
            argb = image.getRGB(0, 0, width, height, null, 0, width);
        }
        byte[] raw = new byte[height * (width * 4 + 1)];
        int out = 0;
        for (int y = 0; y < height; y++) {
            raw[out++] = 0;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = argb[row + x];
                raw[out++] = (byte) (pixel >>> 16);
                raw[out++] = (byte) (pixel >>> 8);
                raw[out++] = (byte) pixel;
                raw[out++] = (byte) (pixel >>> 24);
            }
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(raw.length / 4);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream zip = new DeflaterOutputStream(compressed, deflater, 65536)) {
            zip.write(raw);
        } finally {
            deflater.end();
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream(compressed.size() + 64);
        DataOutputStream data = new DataOutputStream(png);
        data.write(SIGNATURE);
        ByteArrayOutputStream header = new ByteArrayOutputStream(13);
        DataOutputStream headerData = new DataOutputStream(header);
        headerData.writeInt(width);
        headerData.writeInt(height);
        headerData.writeByte(8);
        headerData.writeByte(6);
        headerData.writeByte(0);
        headerData.writeByte(0);
        headerData.writeByte(0);
        chunk(data, "IHDR", header.toByteArray());
        chunk(data, "IDAT", compressed.toByteArray());
        chunk(data, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(DataOutputStream out, String type, byte[] body) throws IOException {
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeInt(body.length);
        out.write(name);
        out.write(body);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body);
        out.writeInt((int) crc.getValue());
    }
}
