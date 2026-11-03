package dev.ledge.common;

/** Pixels per block for the server-rendered map. */
public enum MapResolution {
    LOW(2),
    STANDARD(4),
    HIGH(8),
    ULTRA(16);

    private final int pixelsPerBlock;

    MapResolution(int pixelsPerBlock) {
        this.pixelsPerBlock = pixelsPerBlock;
    }

    public int pixelsPerBlock() {
        return pixelsPerBlock;
    }
}
