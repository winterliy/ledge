package dev.ledge.common;

import java.util.Objects;

/** User-created point marker shared by the web map and Fabric map. */
public record MapMarker(String id, String worldId, String dimensionId, String name,
                        MapCoordinate position, String bannerColorHex) {
    public MapMarker {
        id = requireId(id, "id");
        worldId = requireId(worldId, "worldId");
        dimensionId = requireId(dimensionId, "dimensionId");
        name = requireName(name);
        Objects.requireNonNull(position, "position");
        bannerColorHex = requireHex(bannerColorHex);
    }

    static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            throw new IllegalArgumentException("name must contain 1 to 64 characters");
        }
        return trimmed;
    }

    static String requireHex(String value) {
        Objects.requireNonNull(value, "colorHex");
        String normalized = value.startsWith("#") ? value.substring(1) : value;
        if (!normalized.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("color must be a six-digit hex color");
        }
        return "#" + normalized.toUpperCase(java.util.Locale.ROOT);
    }

    private static String requireId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[a-zA-Z0-9_.-]{1,96}")) {
            throw new IllegalArgumentException(field + " must be a safe identifier");
        }
        return value;
    }
}
