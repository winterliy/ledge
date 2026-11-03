package dev.ledge.common;

import java.util.Objects;

/** Rendering preferences for one world and dimension. */
public record MapWorldConfig(
        String worldId,
        String dimensionId,
        MapRendererType renderer,
        MapResolution resolution,
        boolean liveUpdates
) {
    public MapWorldConfig {
        worldId = requireId(worldId, "worldId");
        dimensionId = requireId(dimensionId, "dimensionId");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(resolution, "resolution");
    }

    private static String requireId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[a-zA-Z0-9_.-]{1,96}")) {
            throw new IllegalArgumentException(field + " must be a safe identifier");
        }
        return value;
    }
}
