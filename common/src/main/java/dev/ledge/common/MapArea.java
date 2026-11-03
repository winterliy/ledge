package dev.ledge.common;

import java.util.List;
import java.util.Objects;

/** Named filled or outline area drawn on a map. */
public record MapArea(String id, String worldId, String dimensionId, String name,
                      List<MapCoordinate> vertices, Style style, String colorHex, int opacity) {
    public enum Style { FILLED, OUTLINE }

    public MapArea {
        id = requireId(id, "id");
        worldId = requireId(worldId, "worldId");
        dimensionId = requireId(dimensionId, "dimensionId");
        name = MapMarker.requireName(name);
        vertices = List.copyOf(Objects.requireNonNull(vertices, "vertices"));
        if (vertices.size() < 3 || vertices.size() > 4096) {
            throw new IllegalArgumentException("an area must have 3 to 4096 vertices");
        }
        Objects.requireNonNull(style, "style");
        colorHex = MapMarker.requireHex(colorHex);
        if (opacity < 1 || opacity > 255) {
            throw new IllegalArgumentException("opacity must be between 1 and 255");
        }
        if (style == Style.OUTLINE && opacity != 255) {
            throw new IllegalArgumentException("outline opacity is fixed at 255");
        }
    }

    private static String requireId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[a-zA-Z0-9_.-]{1,96}")) {
            throw new IllegalArgumentException(field + " must be a safe identifier");
        }
        return value;
    }
}
