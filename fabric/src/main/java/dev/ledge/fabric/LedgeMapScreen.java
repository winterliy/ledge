package dev.ledge.fabric;

import com.mojang.blaze3d.platform.InputConstants;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LedgeMapScreen extends Screen {
    private int centerX;
    private int centerZ;
    /** Tiles drawn left/right and above/below the center tile; updated every frame from the screen size. */
    private int visibleRangeX = 2;
    private int visibleRangeZ = 2;
    /** Diamond bounds of the 3D view in tiles (see renderVirtual). */
    private int projectedDiff = 8;
    private int projectedSum = 8;

    private String targetLabel() { return targetLabel.isEmpty() ? "차원 선택" : targetLabel; }

    /** Button text after the globe icon: the label without its leading "world · " part. */
    private String targetText() {
        String label = targetLabel();
        int dot = label.indexOf(" · ");
        return dot < 0 ? label : label.substring(dot + 3);
    }

    /** The world/dimension button hugs its label (at most 220 px, never into the other buttons). */
    private int targetButtonRight() {
        return TOOLBAR_START + Math.min(220, font.width(targetText()) + 32);
    }

    // 16x16 toolbar icons in the same pixel style as the printer ('#' = white, '+' = light gray).
    private static final String[] REFRESH_ICON = {
            "                ",
            "     #####      ",
            "   ##     ## #  ",
            "  #         ##  ",
            "  #        ###  ",
            " #        ####  ",
            " #              ",
            " #              ",
            " #            # ",
            " #            # ",
            "  #          #  ",
            "  #         #   ",
            "   ##     ##    ",
            "     #####      ",
            "                ",
            "                "};
    private static final String[] GLOBE_ICON = {
            "                ",
            "     ######     ",
            "   ##  ##  ##   ",
            "  #   #  #   #  ",
            " #+++#++++#+++# ",
            " #   #    #   # ",
            "#    #    #    #",
            "################",
            "#    #    #    #",
            " #   #    #   # ",
            " #+++#++++#+++# ",
            "  #   #  #   #  ",
            "   ##  ##  ##   ",
            "     ######     ",
            "                ",
            "                "};
    private static final String[] ZOOM_IN_ICON = {
            "                ",
            "   #####        ",
            "  #     #       ",
            " #   #   #      ",
            " #   #   #      ",
            " # ##### #      ",
            " #   #   #      ",
            " #   #   #      ",
            "  #     #       ",
            "   ##### #      ",
            "          #     ",
            "           ##   ",
            "            ##  ",
            "             ## ",
            "                ",
            "                "};
    private static final String[] ZOOM_OUT_ICON = {
            "                ",
            "   #####        ",
            "  #     #       ",
            " #       #      ",
            " #       #      ",
            " # ##### #      ",
            " #       #      ",
            " #       #      ",
            "  #     #       ",
            "   ##### #      ",
            "          #     ",
            "           ##   ",
            "            ##  ",
            "             ## ",
            "                ",
            "                "};

    private static void drawIcon(GuiGraphicsExtractor graphics, String[] rows, int x, int y) {
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                if (c == ' ') continue;
                graphics.fill(x + col, y + row, x + col + 1, y + row + 1, c == '#' ? 0xffffffff : 0xffaab4bd);
            }
        }
    }

    /** Player-marker toggle: the player's own head, dimmed while the marker is hidden. */
    private void drawPlayerButton(GuiGraphicsExtractor graphics) {
        if (minecraft.player == null) return;
        int x = buttonLeft(PLAYER) + 6;
        int y = 15;
        net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(graphics, minecraft.player.getSkin(), x, y, 16);
        if (!LedgeFabricClient.showPlayerMarker()) graphics.fill(x, y, x + 16, y + 16, 0xb0000000);
    }

    /** 16x14 pixel printer (the font has no printer emoji glyph): paper in, body, output tray, status light. */
    private static void drawPrinterIcon(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x + 4, y, x + 12, y + 4, 0xfff2f2f2);        // paper feeding in
        graphics.fill(x, y + 4, x + 16, y + 10, 0xff8a939b);        // body
        graphics.fill(x + 1, y + 5, x + 15, y + 6, 0xffb9c1c8);     // top highlight
        graphics.fill(x + 12, y + 7, x + 14, y + 8, 0xff5ee36a);    // status light
        graphics.fill(x + 3, y + 9, x + 13, y + 10, 0xff2b3137);    // output slot
        graphics.fill(x + 4, y + 10, x + 12, y + 14, 0xfff2f2f2);   // printed sheet
        graphics.fill(x + 5, y + 11, x + 11, y + 12, 0xffaab4bd);   // text line
    }

    private static final int FACE_SIZE = 12;
    /** Screen position of the player's face marker this frame (MIN_VALUE when hidden). */
    private int playerMarkerX = Integer.MIN_VALUE;
    private int playerMarkerY;

    /** The player's skin face replaces the old red dot; hovering it shows the name. */
    private void drawPlayerFace(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (playerMarkerX == Integer.MIN_VALUE || minecraft.player == null) return;
        int x = playerMarkerX - FACE_SIZE / 2;
        int y = playerMarkerY - FACE_SIZE / 2;
        graphics.fill(x - 1, y - 1, x + FACE_SIZE + 1, y + FACE_SIZE + 1, 0xff000000);
        net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(graphics, minecraft.player.getSkin(), x, y, FACE_SIZE);
        if (mouseX >= x - 1 && mouseX <= x + FACE_SIZE && mouseY >= y - 1 && mouseY <= y + FACE_SIZE && !contextMenuVisible && !annotationDialog) {
            // Drawn here (inside the virtual-GUI scale) so it sits right next to the cursor; vanilla tooltips
            // render later in unscaled coordinates and would land far from the mouse.
            String name = minecraft.player.getName().getString();
            int tx = mouseX + 10;
            int ty = mouseY - 14;
            int tw = font.width(name) + 8;
            if (tx + tw > width) tx = mouseX - 10 - tw;
            if (ty < 0) ty = mouseY + 10;
            graphics.fill(tx, ty, tx + tw, ty + 14, 0xf0100010);
            graphics.outline(tx, ty, tw, 14, 0xff5000ff);
            graphics.text(font, name, tx + 4, ty + 3, 0xffffffff);
        }
    }

    private boolean insideProjectedView(int a, int b) {
        return Math.abs(a - b) <= projectedDiff && Math.abs(a + b) <= projectedSum;
    }
    private int zoom;
    private boolean requested;
    /** The whole top toolbar (buttons + coordinates); the close block collapses it to a single "+". */
    private boolean toolbarVisible = true;
    private int coordinateWidth = 200;
    private String dimension = "";
    private String targetWorldId = "";
    private String targetLabel = "";
    private int targetRenderer;
    private int targetResolution = 1;
    private String playerDimension = "";
    private int settingsVersion;
    private boolean followPlayer = true;
    private boolean threeDimensional;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;
    private double dragX;
    private double dragY;
    private boolean selectingPrint;
    private boolean printArmed;
    private double selectionStartX;
    private double selectionStartY;
    private double selectionEndX;
    private double selectionEndY;
    private boolean contextMenuVisible;
    private int contextMenuX;
    private int contextMenuY;
    /** Marker or Area under the right-click, or null for an empty spot. */
    private Object contextTarget;
    private Object editingTarget;
    private int contextWorldX;
    private int contextWorldZ;
    private boolean awaitingArea;
    private boolean selectingArea;
    private boolean areaFilled;
    private boolean annotationDialog;
    private boolean dialogIsArea;
    private String dialogName = "";
    private String dialogColor = "#F44336";
    private boolean colorFieldActive;
    private int dialogX;
    private int dialogZ;
    private final List<LocalMapAnnotations.Point> areaPoints = new ArrayList<>();
    private List<LocalMapAnnotations.Point> dialogAreaPoints = List.of();
    private LocalMapAnnotations.Point straightEndpoint;
    private double lastAreaSampleX;
    private double lastAreaSampleY;
    private int dialogMinX;
    private int dialogMinZ;
    private int dialogMaxX;
    private int dialogMaxZ;

    public LedgeMapScreen() {
        super(Component.literal("Ledge Map"));
    }

    @Override
    protected void init() {
        if (minecraft.level != null) {
            playerDimension = minecraft.level.dimension().identifier().toString().replace(':', '_');
        }
        LedgeFabricClient.requestSettings();
    }

    @Override
    public void tick() {
        if (minecraft.player == null) return;
        if (!requested && LedgeFabricClient.settingsVersion() > 0) {
            settingsVersion = LedgeFabricClient.settingsVersion();
            setTarget(LedgeFabricClient.serverTarget());
            requestVisibleTiles();
            return;
        }
        int x = Math.floorDiv(minecraft.player.blockPosition().getX(), tileBlocks());
        int z = Math.floorDiv(minecraft.player.blockPosition().getZ(), tileBlocks());
        String currentDimension = minecraft.level.dimension().identifier().toString().replace(':', '_');
        if (!currentDimension.equals(playerDimension)) {
            playerDimension = currentDimension;
            LedgeFabricClient.requestSettings();
        }
        if (settingsVersion != LedgeFabricClient.settingsVersion()) {
            settingsVersion = LedgeFabricClient.settingsVersion();
            var serverTarget = LedgeFabricClient.serverTarget();
            if (followPlayer && serverTarget != null) {
                LedgeFabricClient.unsubscribeTiles();
                setTarget(serverTarget);
                loadAnnotations(dimension);
            }
            if (followPlayer) {
                centerX = Math.floorDiv(minecraft.player.blockPosition().getX(), tileBlocks());
                centerZ = Math.floorDiv(minecraft.player.blockPosition().getZ(), tileBlocks());
            }
            requestCurrentTiles();
        } else if (followPlayer && (!requested || x != centerX || z != centerZ)) {
            centerX = x;
            centerZ = z;
            requestCurrentTiles();
        }
    }

    private void requestVisibleTiles() {
        if (minecraft.player == null) return;
        centerX = Math.floorDiv(minecraft.player.blockPosition().getX(), tileBlocks());
        centerZ = Math.floorDiv(minecraft.player.blockPosition().getZ(), tileBlocks());
        if (targetWorldId.isEmpty()) setTarget(LedgeFabricClient.serverTarget());
        if (dimension.isEmpty()) dimension = minecraft.level.dimension().identifier().toString().replace(':', '_');
        loadAnnotations(dimension);
        requestCurrentTiles();
    }

    private int tileBlocks() {
        return LedgeFabricClient.blocksPerTile(tileZoom());
    }

    private void requestCurrentTiles() {
        boolean projected = threeDimensional && targetRenderer == 0;
        for (int x = centerX - visibleRangeX - 1; x <= centerX + visibleRangeX + 1; x++) {
            for (int z = centerZ - visibleRangeZ - 1; z <= centerZ + visibleRangeZ + 1; z++) {
                if (projected && !insideProjectedView(x - centerX, z - centerZ)) continue;
                LedgeFabricClient.requestTile(targetWorldId, dimension, targetRenderer, targetResolution, x, z, tileZoom());
            }
        }
        requested = true;
    }

    private void setTarget(LedgeFabricClient.MapTarget target) {
        if (target == null) return;
        targetWorldId = target.worldId();
        dimension = target.dimension();
        targetLabel = target.label();
        targetRenderer = target.renderer();
        targetResolution = target.resolution();
        LedgeFabricClient.selectTarget(target);
    }

    private void cycleTarget() {
        var targets = LedgeFabricClient.mapTargets();
        if (targets.isEmpty()) return;
        int current = -1;
        for (int i = 0; i < targets.size(); i++) {
            var target = targets.get(i);
            if (target.worldId().equals(targetWorldId) && target.dimension().equals(dimension)) {
                current = i;
                break;
            }
        }
        var next = targets.get((current + 1) % targets.size());
        LedgeFabricClient.unsubscribeTiles();
        setTarget(next);
        followPlayer = false;
        int blockX = minecraft.player == null ? 0 : minecraft.player.blockPosition().getX();
        int blockZ = minecraft.player == null ? 0 : minecraft.player.blockPosition().getZ();
        centerX = Math.floorDiv(blockX, tileBlocks());
        centerZ = Math.floorDiv(blockZ, tileBlocks());
        loadAnnotations(dimension);
        requestCurrentTiles();
    }


    // The map UI is laid out for at least 760x420 GUI units; on large GUI scales it is drawn smaller
    // and mouse input is mapped into the same virtual space.
    private static final float MIN_LAYOUT_WIDTH = 760f;
    private static final float MIN_LAYOUT_HEIGHT = 420f;
    private int realWidth;
    private int realHeight;
    private float activeScale = 1f;

    private float enterVirtual() {
        realWidth = width;
        realHeight = height;
        activeScale = Math.min(1f, Math.min(realWidth / MIN_LAYOUT_WIDTH, realHeight / MIN_LAYOUT_HEIGHT));
        width = Math.round(realWidth / activeScale);
        height = Math.round(realHeight / activeScale);
        return activeScale;
    }

    private void exitVirtual() {
        width = realWidth;
        height = realHeight;
    }

    private MouseButtonEvent virtualEvent(MouseButtonEvent event, float scale) {
        return new MouseButtonEvent(event.x() / scale, event.y() / scale, event.buttonInfo());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        float scale = enterVirtual();
        try {
            graphics.pose().pushMatrix();
            graphics.pose().scale(scale, scale);
            renderVirtual(graphics, Math.round(mouseX / scale), Math.round(mouseY / scale), delta);
            graphics.pose().popMatrix();
        } finally {
            exitVirtual();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        float scale = enterVirtual();
        try { return clickedVirtual(virtualEvent(event, scale), doubleClick); } finally { exitVirtual(); }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        float scale = enterVirtual();
        try { return draggedVirtual(virtualEvent(event, scale), deltaX / scale, deltaY / scale); } finally { exitVirtual(); }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        float scale = enterVirtual();
        try { return releasedVirtual(virtualEvent(event, scale)); } finally { exitVirtual(); }
    }

    private void renderVirtual(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xff10151a);
        int mapSize = mapSize();
        mapSize = Math.max(96, mapSize);
        int tileSize = mapSize / 3;
        int left = (width - mapSize) / 2;
        int top = (height - mapSize) / 2;
        boolean projectedSurface = threeDimensional && targetRenderer == 0;
        pushMapTransform(graphics, top, mapSize);
        if (projectedSurface) graphics.pose().popMatrix();
        int panX = panOffsetX();
        int panZ = panOffsetZ();
        // The map fills the whole screen; the centered square only anchors the tile grid (tile -1..1 sits inside it).
        int minDx = Math.floorDiv(-left - panX, tileSize) - 1;
        int maxDx = Math.floorDiv(width - left - panX, tileSize) - 1;
        int minDz = Math.floorDiv(-top - panZ, tileSize) - 1;
        int maxDz = Math.floorDiv(height - top - panZ, tileSize) - 1;
        if (projectedSurface) {
            // Isometric view: tile (a, b) from the center lands at x ~ (a - b) / 2, y ~ (a + b) / 4 tile sizes, so the
            // screen is covered by the diamond |a - b| <= width / tile, |a + b| <= 2 * height / tile (plus a margin).
            projectedDiff = width / tileSize + 2;
            projectedSum = 2 * height / tileSize + 3;
            int reach = (projectedDiff + projectedSum) / 2 + 1;
            minDx = minDz = -reach;
            maxDx = maxDz = reach;
        }
        visibleRangeX = Math.max(-minDx, maxDx);
        visibleRangeZ = Math.max(-minDz, maxDz);
        for (int tileX = centerX + minDx; tileX <= centerX + maxDx; tileX++) {
            for (int tileZ = centerZ + minDz; tileZ <= centerZ + maxDz; tileZ++) {
                if (projectedSurface && !insideProjectedView(tileX - centerX, tileZ - centerZ)) continue;
                int x = left + (tileX - centerX + 1) * tileSize + panX;
                int z = top + (tileZ - centerZ + 1) * tileSize + panZ;
                if (projectedSurface) {
                    var projected = LedgeFabricClient.projectedTextureFor(targetWorldId, dimension, targetRenderer,
                            targetResolution, tileX, tileZ, tileZoom());
                    if (projected == null) {
                        graphics.fill(x, z, x + tileSize, z + tileSize / 2, 0xff252c32);
                        continue;
                    }
                    double centerX = left + mapSize / 2.0;
                    double centerY = top + mapSize / 2.0;
                    double dx = x - centerX;
                    double dz = z - centerY;
                    int projectedX = (int) Math.round(centerX + (dx - dz) / 2.0 - tileSize / 2.0);
                    float textureScale = tileSize / 256.0f;
                    int projectedY = (int) Math.round(centerY + (dx + dz) / 4.0 - projected.topOffset() * textureScale);
                    int projectedHeight = Math.max(1, Math.round(projected.height() * textureScale));
                    // blit(texture, x0, y0, x1, y1, u0, u1, v0, v1) takes corner coordinates, not a size.
                    graphics.blit(projected.texture(), projectedX, projectedY, projectedX + tileSize, projectedY + projectedHeight, 0f, 1f, 0f, 1f);
                    continue;
                }
                var texture = LedgeFabricClient.textureFor(targetWorldId, dimension, targetRenderer, targetResolution,
                        tileX, tileZ, tileZoom());
                if (texture == null && !drawFallbackTile(graphics, tileX, tileZ, x, z, tileSize)) {
                    graphics.fill(x, z, x + tileSize, z + tileSize, 0xff252c32);
                    graphics.centeredText(font, "불러오는 중", x + tileSize / 2, z + tileSize / 2, 0xffb8c2ca);
                } else if (texture != null) {
                    graphics.blit(texture, x, z, x + tileSize, z + tileSize, 0f, 1f, 0f, 1f);
                }
            }
        }
        if (projectedSurface) pushMapTransform(graphics, top, mapSize);
        var playerTarget = LedgeFabricClient.serverTarget();
        if (minecraft.player != null && LedgeFabricClient.showPlayerMarker() && playerTarget != null
                && playerTarget.worldId().equals(targetWorldId) && playerTarget.dimension().equals(dimension)) {
            int blocks = tileBlocks();
            double markerX = panX + left + mapSize / 2.0 + (minecraft.player.getX() - (centerX * blocks + blocks / 2.0)) * mapSize / (blocks * 3.0);
            double markerZ = panZ + top + mapSize / 2.0 + (minecraft.player.getZ() - (centerZ * blocks + blocks / 2.0)) * mapSize / (blocks * 3.0);
            if (projectedSurface) {
                double elevation = (minecraft.player.getY() - 64.0) * mapSize / (blocks * 3.0) / 4.0;
                markerX -= elevation * 2.0;
                markerZ -= elevation * 2.0;
            }
            // The face is drawn after the map transform is popped so it stays upright; project the point by hand.
            if (threeDimensional) {
                double cx = width / 2.0;
                double cy = top + mapSize / 2.0;
                double dx = markerX - cx;
                double dz = markerZ - cy;
                markerX = cx + (dx - dz) / 2.0;
                markerZ = cy + (dx + dz) / 4.0;
            }
            playerMarkerX = (int) Math.round(markerX);
            playerMarkerY = (int) Math.round(markerZ);
        } else {
            playerMarkerX = Integer.MIN_VALUE;
        }
        for (var area : LocalMapAnnotations.areas()) {
            int rgb = Integer.parseInt(area.color().substring(1), 16);
            List<LocalMapAnnotations.Point> points = area.points();
            if (points == null || points.size() < 3) {
                points = List.of(new LocalMapAnnotations.Point(area.minX(), area.minZ()),
                        new LocalMapAnnotations.Point(area.maxX(), area.minZ()),
                        new LocalMapAnnotations.Point(area.maxX(), area.maxZ()),
                        new LocalMapAnnotations.Point(area.minX(), area.maxZ()));
            }
            drawPolygon(graphics, points, left, top, mapSize, 0x55000000 | rgb, 0xff000000 | rgb, area.filled());
            var first = points.get(0);
            graphics.text(font, area.name(), mapX(left, mapSize, first.x()) + 3,
                    mapZ(top, mapSize, first.z()) + 3, 0xffffffff);
        }
        for (var marker : LocalMapAnnotations.markers()) {
            int x = mapX(left, mapSize, marker.x());
            int z = mapZ(top, mapSize, marker.z());
            int rgb = Integer.parseInt(marker.color().substring(1), 16);
            graphics.fill(x - 4, z - 4, x + 5, z + 5, 0xff000000 | rgb);
            graphics.text(font, marker.name(), x + 6, z - 5, 0xffffffff);
        }
        graphics.pose().popMatrix();
        drawPlayerFace(graphics, mouseX, mouseY);
        if (!toolbarVisible) {
            // Collapsed: a single "+" block brings the whole toolbar back.
            graphics.fill(10, 10, 10 + TOGGLE_WIDTH, 36, 0x99000000);
            graphics.centeredText(font, "+", 10 + TOGGLE_WIDTH / 2, 18, 0xffffffff);
        } else {
            graphics.fill(buttonLeft(PLAYER), 10, buttonRight(PLAYER), 36, 0x99000000);
            drawPlayerButton(graphics);
            graphics.fill(buttonLeft(ZOOM_OUT), 10, buttonRight(ZOOM_OUT), 36, 0x99000000);
            drawIcon(graphics, ZOOM_OUT_ICON, buttonCenter(ZOOM_OUT) - 8, 15);
            graphics.fill(buttonLeft(ZOOM_IN), 10, buttonRight(ZOOM_IN), 36, 0x99000000);
            drawIcon(graphics, ZOOM_IN_ICON, buttonCenter(ZOOM_IN) - 8, 15);
            graphics.fill(buttonLeft(VIEW), 10, buttonRight(VIEW), 36, 0x99000000);
            graphics.centeredText(font, threeDimensional ? "3D" : "2D", buttonCenter(VIEW), 18, 0xffffffff);
            int targetButtonLeft = TOOLBAR_START;
            int targetButtonRight = targetButtonRight();
            graphics.fill(buttonLeft(REFRESH), 10, buttonRight(REFRESH), 36, 0x99000000);
            drawIcon(graphics, REFRESH_ICON, buttonCenter(REFRESH) - 8, 15);
            graphics.fill(targetButtonLeft, 10, targetButtonRight, 36, 0x99000000);
            drawIcon(graphics, GLOBE_ICON, targetButtonLeft + 6, 15);
            String shownTarget = targetText();
            int textRoom = targetButtonRight - targetButtonLeft - 30;
            if (font.width(shownTarget) > textRoom) {
                shownTarget = font.plainSubstrByWidth(shownTarget, textRoom - font.width("…")) + "…";
            }
            graphics.text(font, shownTarget, targetButtonLeft + 26, 18, 0xffffffff);
            graphics.fill(buttonLeft(PRINT), 10, buttonRight(PRINT), 36, printArmed ? 0xcc3c91e6 : 0x99000000);
            drawPrinterIcon(graphics, buttonLeft(PRINT) + 6, 16);
            if (minecraft.player != null) {
                int worldX;
                int worldZ;
                var pos = minecraft.player.blockPosition();
                MapPosition mapMouse = unprojectMapPosition(mouseX, mouseY, left, top, mapSize);
                if (threeDimensional || (mapMouse.x() >= 0 && mapMouse.x() < width && mapMouse.y() >= 0 && mapMouse.y() < height)) {
                    worldX = (centerX - 1) * tileBlocks() + (int) Math.floor((mapMouse.x() - left - panOffsetX()) * tileBlocks() * 3.0 / mapSize);
                    worldZ = (centerZ - 1) * tileBlocks() + (int) Math.floor((mapMouse.y() - top - panOffsetZ()) * tileBlocks() * 3.0 / mapSize);
                } else {
                    worldX = pos.getX();
                    worldZ = pos.getZ();
                }
                String coordinates = "X " + worldX + "  Z " + worldZ;
                coordinateWidth = font.width(coordinates) + 12;
                graphics.fill(coordLeft(), COORD_TOP, coordLeft() + coordinateWidth, COORD_BOTTOM, 0x99000000);
                graphics.text(font, coordinates, coordLeft() + 6, COORD_TOP + 8, 0xffdce5eb);
            }
            // Separate close block at the end of the row: hides the whole toolbar.
            // The close block sits at the far left, where the "+" appears once the toolbar is closed.
            graphics.fill(10, 10, 10 + TOGGLE_WIDTH, 36, 0x99000000);
            graphics.centeredText(font, "×", 10 + TOGGLE_WIDTH / 2, 18, 0xffffffff);
        }
        // Status messages (refresh, print saved, ...) use the same bottom line as the hints.
        boolean showStatus = statusMessage != null && System.currentTimeMillis() < statusUntil;
        String hint = showStatus ? statusMessage
                : printArmed ? "인쇄: 지도 위를 드래그해 저장할 영역을 선택하세요 · Esc 취소"
                : selectingPrint ? "놓으면 선택한 영역을 screenshots 폴더에 저장합니다"
                : selectingArea ? "자유 선택 · Shift: 가로/세로 직선 · 놓으면 완료" :
                "드래그 이동 · 우클릭으로 포인트/영역 추가";
        int hintWidth = font.width(hint);
        graphics.fill(width / 2 - hintWidth / 2 - 6, height - 22, width / 2 + hintWidth / 2 + 6, height - 6, 0x99000000);
        graphics.centeredText(font, hint, width / 2, height - 18, showStatus ? 0xffffe08a : 0xffdce5eb);
        if (selectingPrint) {
            int sx = (int) Math.min(selectionStartX, selectionEndX);
            int sy = (int) Math.min(selectionStartY, selectionEndY);
            int ex = (int) Math.max(selectionStartX, selectionEndX);
            int ey = (int) Math.max(selectionStartY, selectionEndY);
            graphics.fill(sx, sy, ex, ey, 0x553c91e6);
            graphics.outline(sx, sy, ex - sx, ey - sy, 0xff66b1ff);
        }
        if (selectingArea) {
            pushMapTransform(graphics, top, mapSize);
            List<LocalMapAnnotations.Point> preview = new ArrayList<>(areaPoints);
            LocalMapAnnotations.Point end = straightEndpoint;
            if (end != null && !preview.isEmpty()) preview.add(end);
            drawPolygon(graphics, preview, left, top, mapSize, 0x552196f3, 0xff66b1ff, areaFilled && preview.size() >= 3);
            if (preview.size() > 1) {
                for (int i = 1; i < preview.size(); i++) {
                    var a = preview.get(i - 1);
                    var b = preview.get(i);
                    drawLine(graphics, mapX(left, mapSize, a.x()), mapZ(top, mapSize, a.z()),
                            mapX(left, mapSize, b.x()), mapZ(top, mapSize, b.z()), 0xff66b1ff);
                }
                if (straightEndpoint == null) {
                    var last = preview.get(preview.size() - 1);
                    drawLine(graphics, mapX(left, mapSize, last.x()), mapZ(top, mapSize, last.z()),
                            mapX(left, mapSize, mapWorldX(left, top, mapSize, mouseX, mouseY)),
                            mapZ(top, mapSize, mapWorldZ(left, top, mapSize, mouseX, mouseY)), 0xff66b1ff);
                }
            }
            graphics.pose().popMatrix();
        }
        if (contextMenuVisible) {
            List<String> labels = contextMenuLabels();
            int menuHeight = contextMenuHeight();
            graphics.fill(contextMenuX, contextMenuY, contextMenuX + 150, contextMenuY + menuHeight, 0xf020252b);
            graphics.outline(contextMenuX, contextMenuY, 150, menuHeight, 0xff69737d);
            for (int i = 0; i < labels.size(); i++) {
                boolean danger = labels.get(i).equals("삭제");
                graphics.text(font, labels.get(i), contextMenuX + 8, contextMenuY + 6 + i * 22, danger ? 0xffff7b7b : 0xffffffff);
            }
        }
        if (annotationDialog) {
            int dx = (width - 280) / 2;
            int dy = (height - 118) / 2;
            graphics.fill(dx, dy, dx + 280, dy + 118, 0xf014181d);
            graphics.outline(dx, dy, 280, 118, 0xff77828d);
            graphics.text(font, (editingTarget != null ? "수정: " : "") + (dialogIsArea ? "영역 이름과 색상" : "포인트 이름과 배너 색상"), dx + 12, dy + 10, 0xffffffff);
            graphics.text(font, "이름: " + dialogName + (colorFieldActive ? "" : "_"), dx + 12, dy + 38, 0xffdce5eb);
            graphics.text(font, "HEX: " + dialogColor + (colorFieldActive ? "_" : ""), dx + 12, dy + 62, 0xffdce5eb);
            graphics.text(font, "Tab으로 입력칸 전환 · Enter 저장 · Esc 취소", dx + 12, dy + 92, 0xffaab5be);
        }
    }

    private boolean clickedVirtual(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (annotationDialog) {
            int dy = (height - 118) / 2;
            if (y >= dy + 30 && y <= dy + 54) colorFieldActive = false;
            else if (y >= dy + 56 && y <= dy + 82) colorFieldActive = true;
            return true;
        }
        if (contextMenuVisible) {
            boolean insideMenu = x >= contextMenuX && x <= contextMenuX + 150 && y >= contextMenuY && y <= contextMenuY + contextMenuHeight();
            if (!insideMenu || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
                contextMenuVisible = false;
                return true;
            }
        }
        if (y >= 10 && y <= 36) {
            if (x >= 10 && x <= 10 + TOGGLE_WIDTH) {
                toolbarVisible = !toolbarVisible;
                return true;
            }
        }
        if (onButton(PLAYER, x, y)) {
            LedgeFabricClient.togglePlayerMarker();
            return true;
        }
        if (onButton(ZOOM_OUT, x, y)) {
            zoomOut();
            return true;
        }
        if (onButton(ZOOM_IN, x, y)) {
            zoomIn();
            return true;
        }
        if (onButton(VIEW, x, y)) {
            threeDimensional = !threeDimensional;
            return true;
        }
        if (onButton(REFRESH, x, y)) {
            LedgeFabricClient.forgetTiles();
            requestCurrentTiles();
            showStatus("지도 타일을 다시 불러옵니다.");
            return true;
        }
        if (toolbarVisible && y >= 10 && y <= 36 && x >= TOOLBAR_START && x <= targetButtonRight()) {
            cycleTarget();
            return true;
        }
        if (onButton(PRINT, x, y)) {
            printArmed = true;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && onMap(x, y)) {
            contextMenuVisible = true;
            contextMenuX = Math.min((int) x, width - 160);
            contextMenuY = Math.min((int) y, height - 76);
            int size = mapSize();
            contextWorldX = mapWorldX((width - size) / 2, (height - size) / 2, size, (int) x, (int) y);
            contextWorldZ = mapWorldZ((width - size) / 2, (height - size) / 2, size, (int) x, (int) y);
            contextTarget = annotationAt((width - size) / 2, (height - size) / 2, size, x, y);
            contextMenuY = Math.min(contextMenuY, height - contextMenuHeight() - 10);
            return true;
        }
        if (contextMenuVisible && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int option = (int) ((y - contextMenuY) / 22);
            List<String> labels = contextMenuLabels();
            boolean selected = x >= contextMenuX && x <= contextMenuX + 150
                    && y >= contextMenuY && y <= contextMenuY + contextMenuHeight() && option < labels.size();
            contextMenuVisible = false;
            if (!selected) return true;
            if (contextTarget instanceof LocalMapAnnotations.Marker marker) {
                if (option == 0) openEditDialog(marker, marker.name(), marker.color(), false);
                else LocalMapAnnotations.removeMarker(marker);
            } else if (contextTarget instanceof LocalMapAnnotations.Area area) {
                if (option == 0) openEditDialog(area, area.name(), area.color(), true);
                else if (option == 1) LocalMapAnnotations.replaceArea(area, new LocalMapAnnotations.Area(area.minX(), area.minZ(),
                        area.maxX(), area.maxZ(), area.name(), area.color(), !area.filled(), area.points()));
                else LocalMapAnnotations.removeArea(area);
            } else if (option == 0) {
                dialogIsArea = false;
                dialogX = contextWorldX;
                dialogZ = contextWorldZ;
                openAnnotationDialog();
            } else {
                areaFilled = option == 1;
                awaitingArea = true;
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && x > 20 && x < width - 20 && y > 20 && y < height - 20) {
            if (printArmed) {
                printArmed = false;
                selectingPrint = true;
                selectionStartX = selectionEndX = x;
                selectionStartY = selectionEndY = y;
                return true;
            }
            if (awaitingArea && onMap(x, y)) {
                awaitingArea = false;
                selectingArea = true;
                areaPoints.clear();
                straightEndpoint = null;
                selectionStartX = selectionEndX = x;
                selectionStartY = selectionEndY = y;
                int size = mapSize();
                areaPoints.add(new LocalMapAnnotations.Point(
                        mapWorldX((width - size) / 2, (height - size) / 2, size, (int) x, (int) y),
                        mapWorldZ((width - size) / 2, (height - size) / 2, size, (int) x, (int) y)));
                lastAreaSampleX = x;
                lastAreaSampleY = y;
                return true;
            }
            dragging = true;
            lastMouseX = x;
            lastMouseY = y;
            followPlayer = false;
            return true;
        }
        return false;
    }

    private boolean draggedVirtual(MouseButtonEvent event, double deltaX, double deltaY) {
        if (selectingArea) {
            selectionEndX = event.x();
            selectionEndY = event.y();
            int size = mapSize();
            var point = new LocalMapAnnotations.Point(
                    mapWorldX((width - size) / 2, (height - size) / 2, size, (int) event.x(), (int) event.y()),
                    mapWorldZ((width - size) / 2, (height - size) / 2, size, (int) event.x(), (int) event.y()));
            if (minecraft.hasShiftDown()) {
                straightEndpoint = axisAligned(point);
                return true;
            }
            if (straightEndpoint != null) {
                appendAreaPoint(straightEndpoint);
                straightEndpoint = null;
                lastAreaSampleX = event.x();
                lastAreaSampleY = event.y();
            }
            if (Math.hypot(event.x() - lastAreaSampleX, event.y() - lastAreaSampleY) >= 4.0) {
                appendAreaPoint(point);
                lastAreaSampleX = event.x();
                lastAreaSampleY = event.y();
            }
            return true;
        }
        if (selectingPrint) {
            selectionEndX = event.x();
            selectionEndY = event.y();
            return true;
        }
        if (!dragging) return false;
        double screenDeltaX = event.x() - lastMouseX;
        double screenDeltaY = event.y() - lastMouseY;
        if (threeDimensional) {
            dragX += screenDeltaX + 2.0 * screenDeltaY;
            dragY += -screenDeltaX + 2.0 * screenDeltaY;
        } else {
            dragX += screenDeltaX;
            dragY += screenDeltaY;
        }
        lastMouseX = event.x();
        lastMouseY = event.y();
        int mapSize = mapSize();
        int tileSize = Math.max(96, mapSize) / 3;
        // Keep the sub-tile remainder as a pixel offset so panning (2D and 3D) follows the mouse smoothly.
        int shiftX = (int) Math.floor((dragX + tileSize / 2.0) / tileSize);
        int shiftZ = (int) Math.floor((dragY + tileSize / 2.0) / tileSize);
        if (shiftX != 0 || shiftZ != 0) {
            centerX -= shiftX;
            centerZ -= shiftZ;
            dragX -= (double) shiftX * tileSize;
            dragY -= (double) shiftZ * tileSize;
            requestCurrentTiles();
        }
        return true;
    }

    private boolean releasedVirtual(MouseButtonEvent event) {
        if (selectingArea && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            selectingArea = false;
            int size = mapSize();
            var end = new LocalMapAnnotations.Point(
                    mapWorldX((width - size) / 2, (height - size) / 2, size, (int) event.x(), (int) event.y()),
                    mapWorldZ((width - size) / 2, (height - size) / 2, size, (int) event.x(), (int) event.y()));
            if (minecraft.hasShiftDown()) {
                straightEndpoint = axisAligned(end);
                appendAreaPoint(straightEndpoint);
            } else {
                if (straightEndpoint != null) appendAreaPoint(straightEndpoint);
                appendAreaPoint(end);
            }
            if (areaPoints.size() >= 3 && hasThreeDistinctAreaPoints()) {
                dialogIsArea = true;
                dialogAreaPoints = List.copyOf(areaPoints);
                dialogMinX = areaPoints.stream().mapToInt(LocalMapAnnotations.Point::x).min().orElse(0);
                dialogMaxX = areaPoints.stream().mapToInt(LocalMapAnnotations.Point::x).max().orElse(0);
                dialogMinZ = areaPoints.stream().mapToInt(LocalMapAnnotations.Point::z).min().orElse(0);
                dialogMaxZ = areaPoints.stream().mapToInt(LocalMapAnnotations.Point::z).max().orElse(0);
                openAnnotationDialog();
            } else if (minecraft.player != null) {
                showStatus("영역을 만들려면 넓이가 있는 점 세 개 이상이 필요합니다.");
            }
            areaPoints.clear();
            straightEndpoint = null;
            return true;
        }
        if (selectingPrint && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            selectionEndX = event.x();
            selectionEndY = event.y();
            selectingPrint = false;
            saveSelectedScreenshot();
            return true;
        }
        if (dragging && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = false;
            return true;
        }
        return false;
    }

    /** Saves the selected part of the 2D map as an image built from the received tile pixels (no UI). */
    private void saveSelectedScreenshot() {
        int size = mapSize();
        int left = (width - size) / 2;
        int top = (height - size) / 2;
        int sx0 = (int) Math.min(selectionStartX, selectionEndX);
        int sy0 = (int) Math.min(selectionStartY, selectionEndY);
        int sx1 = (int) Math.max(selectionStartX, selectionEndX);
        int sy1 = (int) Math.max(selectionStartY, selectionEndY);
        if (sx1 - sx0 < 4 || sy1 - sy0 < 4) {
            showStatus("인쇄 영역이 너무 작습니다. 더 크게 드래그하세요.");
            return;
        }
        boolean wasThreeDimensional = threeDimensional;
        threeDimensional = false;
        // World range of the selection (may extend past the map frame) and of the visible map frame.
        int worldX0 = mapWorldX(left, top, size, sx0, sy0);
        int worldZ0 = mapWorldZ(left, top, size, sx0, sy0);
        int worldX1 = mapWorldX(left, top, size, sx1, sy1);
        int worldZ1 = mapWorldZ(left, top, size, sx1, sy1);
        int frameX0 = mapWorldX(left, top, size, 0, 0);
        int frameZ0 = mapWorldZ(left, top, size, 0, 0);
        int frameX1 = mapWorldX(left, top, size, width, height);
        int frameZ1 = mapWorldZ(left, top, size, width, height);
        threeDimensional = wasThreeDimensional;
        int blocks = tileBlocks();
        double pixelsPerBlock = 256.0 / blocks;
        int outWidth = (int) Math.round((worldX1 - worldX0) * pixelsPerBlock);
        int outHeight = (int) Math.round((worldZ1 - worldZ0) * pixelsPerBlock);
        if (outWidth < 4 || outHeight < 4 || outWidth > 8192 || outHeight > 8192) {
            showStatus("인쇄할 수 없는 크기입니다.");
            return;
        }
        int mapX0 = Math.max(worldX0, frameX0);
        int mapZ0 = Math.max(worldZ0, frameZ0);
        int mapX1 = Math.min(worldX1, frameX1);
        int mapZ1 = Math.min(worldZ1, frameZ1);
        try (NativeImage out = new NativeImage(outWidth, outHeight, true)) {
            // The selection starts as map-screen background (NativeImage pixels are ARGB in 26.3), then map pixels go on top.
            out.fillRect(0, 0, outWidth, outHeight, MAP_BACKGROUND);
            if (mapX1 > mapX0 && mapZ1 > mapZ0) {
                for (int tileX = Math.floorDiv(mapX0, blocks); tileX <= Math.floorDiv(mapX1 - 1, blocks); tileX++) {
                    for (int tileZ = Math.floorDiv(mapZ0, blocks); tileZ <= Math.floorDiv(mapZ1 - 1, blocks); tileZ++) {
                        byte[] png = LedgeFabricClient.tilePng(targetWorldId, dimension, targetRenderer, targetResolution, tileX, tileZ, tileZoom());
                        if (png == null) continue;
                        try (NativeImage tile = NativeImage.read(png)) {
                            for (int py = 0; py < tile.getHeight(); py++) {
                                double worldZ = tileZ * (double) blocks + py / pixelsPerBlock;
                                if (worldZ < mapZ0 || worldZ >= mapZ1) continue;
                                int oy = (int) Math.round((worldZ - worldZ0) * pixelsPerBlock);
                                if (oy < 0 || oy >= outHeight) continue;
                                for (int px = 0; px < tile.getWidth(); px++) {
                                    double worldX = tileX * (double) blocks + px / pixelsPerBlock;
                                    if (worldX < mapX0 || worldX >= mapX1) continue;
                                    int ox = (int) Math.round((worldX - worldX0) * pixelsPerBlock);
                                    if (ox < 0 || ox >= outWidth) continue;
                                    int pixel = tile.getPixel(px, py);
                                    if ((pixel >>> 24) != 0) out.setPixel(ox, oy, pixel);
                                }
                            }
                        }
                    }
                }
            }
            Path directory = minecraft.gameDirectory.toPath().resolve("screenshots");
            String name = "ledge-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png";
            Files.createDirectories(directory);
            out.writeToFile(directory.resolve(name));
            showStatus("인쇄 저장: screenshots/" + name);
        } catch (IOException exception) {
            showStatus("인쇄 저장 실패: " + exception.getMessage());
        }
    }

    private static final int TOGGLE_WIDTH = 28;
    /** The coordinate panel ends the single left-aligned toolbar row, right after the print button. */
    private static final int COORD_TOP = 10;
    private static final int COORD_BOTTOM = 36;

    private int coordLeft() { return buttonRight(PRINT) + BUTTON_GAP; }

    /** Left edge of the world button: the open/close toggle occupies the far left. */
    private static final int TOOLBAR_START = 10 + 28 + 6;

    // Toolbar buttons after the world button, packed left to right: refresh, 2D/3D, -, +, player, print.
    private static final int REFRESH = 0, VIEW = 1, ZOOM_OUT = 2, ZOOM_IN = 3, PLAYER = 4, PRINT = 5;
    private static final int[] BUTTON_WIDTHS = {38, 44, 28, 28, 28, 28};
    private static final int BUTTON_GAP = 6;

    private int buttonLeft(int button) {
        int x = targetButtonRight() + BUTTON_GAP;
        for (int i = 0; i < button; i++) x += BUTTON_WIDTHS[i] + BUTTON_GAP;
        return x;
    }

    private int buttonRight(int button) { return buttonLeft(button) + BUTTON_WIDTHS[button]; }

    private int buttonCenter(int button) { return buttonLeft(button) + BUTTON_WIDTHS[button] / 2; }

    private boolean onButton(int button, double x, double y) {
        return toolbarVisible && y >= 10 && y <= 36 && x >= buttonLeft(button) && x <= buttonRight(button);
    }
    private static final int MAP_BACKGROUND = 0xff10151a;
    private String statusMessage;
    private long statusUntil;

    /** Shows a short message inside the map screen (HUD overlay messages are hidden while it is open). */
    private void showStatus(String message) {
        statusMessage = message;
        statusUntil = System.currentTimeMillis() + 4000;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontalAmount, double verticalAmount) {
        // Trackpads send many small scroll events; collect them and change one level per notch-sized step.
        long now = System.currentTimeMillis();
        if (now - lastScrollMillis > 400) scrollAccumulator = 0;
        lastScrollMillis = now;
        scrollAccumulator += verticalAmount;
        if (Math.abs(scrollAccumulator) >= SCROLL_STEP && now - lastZoomMillis >= 150) {
            if (scrollAccumulator > 0) zoomIn(); else zoomOut();
            scrollAccumulator = 0;
            lastZoomMillis = now;
        }
        return true;
    }

    private static final double SCROLL_STEP = 2.5;
    /** Keypad minus in 26.3's key code table (no named InputConstants field). */
    private static final int KEYPAD_MINUS = 86;
    private double scrollAccumulator;
    private long lastScrollMillis;
    private long lastZoomMillis;

    /**
     * Below 0 the client magnifies the finest server tiles (zoom -1 = 2x, -3 = 8x); the server only knows 0 and up.
     */
    private static final int MIN_ZOOM = -3;

    /** Zoom level of the tiles to request/draw: the finest server level while magnifying. */
    private int tileZoom() { return Math.max(0, zoom); }

    /** On-screen enlargement of zoom-0 tiles when zoomed in past the server's finest level. */
    private int magnification() { return zoom < 0 ? 1 << -zoom : 1; }

    /** A higher zoom level covers more blocks per tile, so zooming in lowers it. */
    private void zoomIn() { changeZoom(-1); }

    private void zoomOut() { changeZoom(1); }

    private void changeZoom(int amount) {
        int nextZoom = Math.max(MIN_ZOOM, Math.min(12, zoom + amount));
        if (nextZoom != zoom) {
            zoom = nextZoom;
            followPlayer = true;
            dragX = 0;
            dragY = 0;
            requestVisibleTiles();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean escape = key == InputConstants.KEY_ESCAPE;
        if (annotationDialog) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                saveAnnotation();
                return true;
            }
            if (escape) {
                closeAnnotationDialog();
                dialogAreaPoints = List.of();
                return true;
            }
            if (key == InputConstants.KEY_TAB) {
                colorFieldActive = !colorFieldActive;
                return true;
            }
            if (key == InputConstants.KEY_BACKSPACE) {
                if (colorFieldActive && !dialogColor.isEmpty()) dialogColor = dialogColor.substring(0, dialogColor.length() - 1);
                else if (!colorFieldActive && !dialogName.isEmpty()) dialogName = dialogName.substring(0, dialogName.length() - 1);
                return true;
            }
            // Let printable keys reach charTyped.
            return false;
        }
        boolean shortcut = event.hasControlDown() || event.hasControlDownWithQuirk();
        if (shortcut && (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD)) {
            zoomIn();
            return true;
        }
        if (shortcut && (key == InputConstants.KEY_MINUS || key == KEYPAD_MINUS)) {
            zoomOut();
            return true;
        }
        if (escape && contextMenuVisible) {
            contextMenuVisible = false;
            return true;
        }
        if (escape && (printArmed || selectingPrint)) {
            printArmed = false;
            selectingPrint = false;
            return true;
        }
        if (escape && (awaitingArea || selectingArea)) {
            awaitingArea = false;
            selectingArea = false;
            areaPoints.clear();
            straightEndpoint = null;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!annotationDialog || !event.isAllowedChatCharacter()) return super.charTyped(event);
        String value = event.codepointAsString();
        if (colorFieldActive) {
            if (dialogColor.length() < 7 && value.matches("[0-9a-fA-F#]")) dialogColor += value;
        } else if (dialogName.length() < 64) {
            dialogName += value;
        }
        return true;
    }

    private void openAnnotationDialog() {
        annotationDialog = true;
        colorFieldActive = false;
        dialogName = dialogIsArea ? "영역" : "포인트";
        dialogColor = dialogIsArea ? "#4A90E2" : "#F44336";
        // 26.3 only delivers typed characters (and IME text) while text input is enabled.
        minecraft.onTextInputFocusChange(this, true);
    }

    private void openEditDialog(Object target, String name, String color, boolean area) {
        dialogIsArea = area;
        openAnnotationDialog();
        editingTarget = target;
        dialogName = name;
        dialogColor = color;
    }

    private List<String> contextMenuLabels() {
        if (contextTarget instanceof LocalMapAnnotations.Marker) return List.of("이름·색상 수정", "삭제");
        if (contextTarget instanceof LocalMapAnnotations.Area area)
            return List.of("이름·색상 수정", area.filled() ? "아웃라인으로 전환" : "채우기로 전환", "삭제");
        return List.of("포인트 표시하기", "영역 채우기", "아웃라인 만들기");
    }

    private int contextMenuHeight() {
        return contextMenuLabels().size() * 22;
    }

    /** Finds the marker (within 6 px) or the area containing the clicked map position. */
    private Object annotationAt(int left, int top, int size, double x, double y) {
        for (var marker : LocalMapAnnotations.markers()) {
            if (Math.abs(mapX(left, size, marker.x()) - x) <= 6 && Math.abs(mapZ(top, size, marker.z()) - y) <= 6) return marker;
        }
        int worldX = mapWorldX(left, top, size, (int) x, (int) y);
        int worldZ = mapWorldZ(left, top, size, (int) x, (int) y);
        var areas = LocalMapAnnotations.areas();
        for (int i = areas.size() - 1; i >= 0; i--) {
            if (contains(areas.get(i).points(), worldX + 0.5, worldZ + 0.5)) return areas.get(i);
        }
        return null;
    }

    private static boolean contains(List<LocalMapAnnotations.Point> points, double x, double z) {
        boolean inside = false;
        for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
            var a = points.get(i);
            var b = points.get(j);
            if ((a.z() > z) != (b.z() > z) && x < (double) (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) inside = !inside;
        }
        return inside;
    }

    private void closeAnnotationDialog() {
        annotationDialog = false;
        editingTarget = null;
        minecraft.onTextInputFocusChange(this, false);
    }



    private void saveAnnotation() {
        String name = dialogName.trim();
        if (name.isEmpty() || !dialogColor.matches("#[0-9a-fA-F]{6}")) {
            showStatus("이름과 #RRGGBB 색상을 확인해 주세요.");
            return;
        }
        String color = dialogColor.toUpperCase(java.util.Locale.ROOT);
        if (editingTarget instanceof LocalMapAnnotations.Marker marker) {
            LocalMapAnnotations.replaceMarker(marker, new LocalMapAnnotations.Marker(marker.x(), marker.z(), name, color));
            editingTarget = null;
            closeAnnotationDialog();
            return;
        }
        if (editingTarget instanceof LocalMapAnnotations.Area area) {
            LocalMapAnnotations.replaceArea(area, new LocalMapAnnotations.Area(area.minX(), area.minZ(), area.maxX(), area.maxZ(),
                    name, color, area.filled(), area.points()));
            editingTarget = null;
            closeAnnotationDialog();
            return;
        }
        if (dialogIsArea) {
            LocalMapAnnotations.addArea(new LocalMapAnnotations.Area(dialogMinX, dialogMinZ, dialogMaxX, dialogMaxZ,
                    name, color, areaFilled, dialogAreaPoints));
            dialogAreaPoints = List.of();
        } else {
            LocalMapAnnotations.addMarker(new LocalMapAnnotations.Marker(dialogX, dialogZ, name, color));
        }
        closeAnnotationDialog();
    }

    private void loadAnnotations(String targetDimension) {
        var server = minecraft.getCurrentServer();
        String serverId = server == null ? "singleplayer" : server.ip;
        LocalMapAnnotations.load(minecraft.gameDirectory.toPath(), serverId, targetWorldId, targetDimension);
    }

    private int mapSize() {
        return Math.max(96, Math.min(height - 100, width - 300)) * magnification();
    }

    private boolean onMap(double x, double y) {
        int size = mapSize();
        int left = (width - size) / 2;
        int top = (height - size) / 2;
        // The whole screen is map now (buttons are checked before this).
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    private int mapWorldX(int left, int top, int size, int x, int y) {
        MapPosition mapPosition = unprojectMapPosition(x, y, left, top, size);
        return (centerX - 1) * tileBlocks() + (int) Math.floor((mapPosition.x() - left - panOffsetX()) * tileBlocks() * 3.0 / size);
    }

    private int mapWorldZ(int left, int top, int size, int x, int y) {
        MapPosition mapPosition = unprojectMapPosition(x, y, left, top, size);
        return (centerZ - 1) * tileBlocks() + (int) Math.floor((mapPosition.y() - top - panOffsetZ()) * tileBlocks() * 3.0 / size);
    }

    private MapPosition unprojectMapPosition(double screenX, double screenY, int left, int top, int size) {
        if (!threeDimensional) return new MapPosition(screenX, screenY);
        double centerX = left + size / 2.0;
        double centerY = top + size / 2.0;
        double dx = screenX - centerX;
        double dy = screenY - centerY;
        return new MapPosition(centerX + dx + 2.0 * dy, centerY - dx + 2.0 * dy);
    }

    // outline(x, y, width, height, color) takes a size; fill(x0, y0, x1, y1, color) takes corners.
    private void pushMapTransform(GuiGraphicsExtractor graphics, int top, int size) {
        graphics.pose().pushMatrix();
        if (!threeDimensional) return;
        float centerX = width / 2.0f;
        float centerY = top + size / 2.0f;
        graphics.pose().translate(centerX, centerY);
        graphics.pose().scale(0.7071068f, 0.3535534f);
        graphics.pose().rotate((float) (Math.PI / 4.0));
        graphics.pose().translate(-centerX, -centerY);
    }

    /**
     * Xaero-style placeholder while a tile of the current zoom is loading: stretch the matching part of an
     * already received coarser tile, or shrink four finer tiles into the cell.
     */
    private boolean drawFallbackTile(GuiGraphicsExtractor graphics, int tileX, int tileZ, int x, int z, int tileSize) {
        for (int level = 1; level <= 3 && tileZoom() + level <= 12; level++) {
            int scale = 1 << level;
            int parentX = Math.floorDiv(tileX, scale);
            int parentZ = Math.floorDiv(tileZ, scale);
            var parent = LedgeFabricClient.textureFor(targetWorldId, dimension, targetRenderer, targetResolution,
                    parentX, parentZ, tileZoom() + level);
            if (parent == null) continue;
            float u0 = (tileX - parentX * scale) / (float) scale;
            float v0 = (tileZ - parentZ * scale) / (float) scale;
            graphics.blit(parent, x, z, x + tileSize, z + tileSize, u0, u0 + 1f / scale, v0, v0 + 1f / scale);
            return true;
        }
        if (tileZoom() == 0) return false;
        boolean drewAny = false;
        int half = tileSize / 2;
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                var child = LedgeFabricClient.textureFor(targetWorldId, dimension, targetRenderer, targetResolution,
                        tileX * 2 + dx, tileZ * 2 + dz, tileZoom() - 1);
                if (child == null) continue;
                int cx = x + dx * half;
                int cz = z + dz * half;
                int cx1 = dx == 0 ? x + half : x + tileSize;
                int cz1 = dz == 0 ? z + half : z + tileSize;
                graphics.blit(child, cx, cz, cx1, cz1, 0f, 1f, 0f, 1f);
                drewAny = true;
            }
        }
        return drewAny;
    }

    // dragX/dragY are kept in map space (3D drags are unprojected), so both views pan by the sub-tile remainder.
    private int panOffsetX() { return (int) Math.round(dragX); }

    private int panOffsetZ() { return (int) Math.round(dragY); }

    private int mapX(int left, int size, int worldX) {
        return left + panOffsetX() + (int) ((worldX - (centerX - 1) * tileBlocks()) * size / (tileBlocks() * 3.0));
    }

    private int mapZ(int top, int size, int worldZ) {
        return top + panOffsetZ() + (int) ((worldZ - (centerZ - 1) * tileBlocks()) * size / (tileBlocks() * 3.0));
    }

    /** With Shift held, the segment from the last point runs only horizontally or vertically. */
    private LocalMapAnnotations.Point axisAligned(LocalMapAnnotations.Point point) {
        if (areaPoints.isEmpty()) return point;
        var last = areaPoints.get(areaPoints.size() - 1);
        return Math.abs(point.x() - last.x()) >= Math.abs(point.z() - last.z())
                ? new LocalMapAnnotations.Point(point.x(), last.z())
                : new LocalMapAnnotations.Point(last.x(), point.z());
    }

    private void appendAreaPoint(LocalMapAnnotations.Point point) {
        if (areaPoints.size() >= 4096) return;
        if (areaPoints.isEmpty() || !areaPoints.get(areaPoints.size() - 1).equals(point)) areaPoints.add(point);
    }

    private boolean hasThreeDistinctAreaPoints() {
        if (areaPoints.stream().distinct().limit(3).count() < 3) return false;
        var origin = areaPoints.get(0);
        for (int i = 1; i + 1 < areaPoints.size(); i++) {
            var first = areaPoints.get(i);
            var second = areaPoints.get(i + 1);
            long cross = (long) (first.x() - origin.x()) * (second.z() - origin.z())
                    - (long) (first.z() - origin.z()) * (second.x() - origin.x());
            if (cross != 0) return true;
        }
        return false;
    }

    private void drawPolygon(GuiGraphicsExtractor graphics, List<LocalMapAnnotations.Point> points,
                            int left, int top, int size, int fillColor, int outlineColor, boolean filled) {
        if (points == null || points.size() < 3) return;
        List<ScreenPoint> polygon = points.stream()
                .map(point -> new ScreenPoint(mapX(left, size, point.x()), mapZ(top, size, point.z())))
                .toList();
        if (filled) {
            int minY = Math.max(0, polygon.stream().mapToInt(ScreenPoint::y).min().orElse(0));
            int maxY = Math.min(height, polygon.stream().mapToInt(ScreenPoint::y).max().orElse(0));
            int rowStep = Math.max(1, (maxY - minY + 127) / 128);
            List<Integer> intersections = new ArrayList<>(polygon.size());
            for (int y = minY; y < maxY; y += rowStep) {
                intersections.clear();
                for (int i = 0; i < polygon.size(); i++) {
                    ScreenPoint a = polygon.get(i);
                    ScreenPoint b = polygon.get((i + 1) % polygon.size());
                    if ((a.y() <= y && b.y() > y) || (b.y() <= y && a.y() > y)) {
                        intersections.add((int) Math.round(a.x() + (double) (y - a.y()) * (b.x() - a.x()) / (b.y() - a.y())));
                    }
                }
                intersections.sort(Comparator.naturalOrder());
                for (int i = 0; i + 1 < intersections.size(); i += 2) {
                    int x1 = Math.max(0, intersections.get(i));
                    int x2 = Math.min(width, intersections.get(i + 1));
                    if (x2 > x1) graphics.fill(x1, y, x2, Math.min(y + rowStep, height), fillColor);
                }
            }
        }
        for (int i = 0; i < polygon.size(); i++) {
            ScreenPoint a = polygon.get(i);
            ScreenPoint b = polygon.get((i + 1) % polygon.size());
            drawLine(graphics, a.x(), a.y(), b.x(), b.y(), outlineColor);
        }
    }

    private void drawLine(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        int distance = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        int step = Math.max(1, (distance + 1023) / 1024);
        for (int i = 0; i <= distance; i += step) {
            double ratio = distance == 0 ? 0 : (double) i / distance;
            int x = (int) Math.round(x1 + (x2 - x1) * ratio);
            int y = (int) Math.round(y1 + (y2 - y1) * ratio);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, color);
        }
    }

    private record ScreenPoint(int x, int y) {}
    private record MapPosition(double x, double y) {}

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (annotationDialog) closeAnnotationDialog();
        LedgeFabricClient.unsubscribeTiles();
    }
}
