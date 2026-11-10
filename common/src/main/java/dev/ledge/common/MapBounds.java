package dev.ledge.common;

/** Inclusive rectangular selection in block coordinates. */
@com.github.bsideup.jabel.Desugar
public record MapBounds(int minX, int minZ, int maxX, int maxZ) {
    public MapBounds {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("bounds must be normalized");
        }
    }

    public static MapBounds between(MapCoordinate first, MapCoordinate second) {
        return new MapBounds(
                Math.min(first.x(), second.x()),
                Math.min(first.z(), second.z()),
                Math.max(first.x(), second.x()),
                Math.max(first.z(), second.z())
        );
    }

    public long width() {
        return (long) maxX - minX + 1;
    }

    public long depth() {
        return (long) maxZ - minZ + 1;
    }

    public long area() {
        return Math.multiplyExact(width(), depth());
    }
}
