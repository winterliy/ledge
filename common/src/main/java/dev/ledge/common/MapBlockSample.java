package dev.ledge.common;

/** Immutable map-safe snapshot produced by a platform adapter on its world thread. */
public record MapBlockSample(int mapColorArgb, int height, boolean caveExposed) {
}
