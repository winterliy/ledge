package dev.ledge.fabric;

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

public final class LedgeMapScreen extends Screen {
    private int centerX;
    private int centerZ;
    private int zoom;
    private boolean requested;
    private boolean coordinatesVisible = true;
    private String dimension = "";
    private int settingsVersion;
    private boolean followPlayer = true;
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
    private int areaStartX;
    private int areaStartZ;
    private int areaEndX;
    private int areaEndZ;
    private int dialogMinX;
    private int dialogMinZ;
    private int dialogMaxX;
    private int dialogMaxZ;

    public LedgeMapScreen() {
        super(Component.literal("Ledge Map"));
    }

    @Override
    protected void init() {
        LedgeFabricClient.requestSettings();
    }

    @Override
    public void tick() {
        if (minecraft.player == null) return;
        if (!requested && LedgeFabricClient.settingsVersion() > 0) {
            settingsVersion = LedgeFabricClient.settingsVersion();
            requestVisibleTiles();
            return;
        }
        int x = Math.floorDiv(minecraft.player.blockPosition().getX(), tileBlocks());
        int z = Math.floorDiv(minecraft.player.blockPosition().getZ(), tileBlocks());
        String currentDimension = minecraft.level.dimension().identifier().toString().replace(':', '_');
        if (requested && !currentDimension.equals(dimension)) LedgeFabricClient.unsubscribeTiles();
        if (settingsVersion != LedgeFabricClient.settingsVersion()) {
            settingsVersion = LedgeFabricClient.settingsVersion();
            if (followPlayer) {
                centerX = x;
                centerZ = z;
            }
            requestCurrentTiles();
        } else if (followPlayer && (!requested || x != centerX || z != centerZ || !currentDimension.equals(dimension))) {
            centerX = x;
            centerZ = z;
            if (!currentDimension.equals(dimension)) loadAnnotations(currentDimension);
            dimension = currentDimension;
            requestCurrentTiles();
        } else if (!currentDimension.equals(dimension)) {
            loadAnnotations(currentDimension);
            dimension = currentDimension;
            centerX = x;
            centerZ = z;
            requestCurrentTiles();
        }
    }

    private void requestVisibleTiles() {
        if (minecraft.player == null) return;
        centerX = Math.floorDiv(minecraft.player.blockPosition().getX(), tileBlocks());
        centerZ = Math.floorDiv(minecraft.player.blockPosition().getZ(), tileBlocks());
        dimension = minecraft.level.dimension().identifier().toString().replace(':', '_');
        loadAnnotations(dimension);
        requestCurrentTiles();
    }

    private int tileBlocks() {
        return LedgeFabricClient.blocksPerTile(zoom);
    }

    private void requestCurrentTiles() {
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) LedgeFabricClient.requestTile(x, z, zoom);
        }
        requested = true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xff10151a);
        int mapSize = Math.min(height - 36, width - 300);
        mapSize = Math.max(96, mapSize);
        int tileSize = mapSize / 3;
        int left = (width - mapSize) / 2;
        int top = (height - mapSize) / 2;
        for (int tileX = centerX - 1; tileX <= centerX + 1; tileX++) {
            for (int tileZ = centerZ - 1; tileZ <= centerZ + 1; tileZ++) {
                int x = left + (tileX - centerX + 1) * tileSize;
                int z = top + (tileZ - centerZ + 1) * tileSize;
                var texture = LedgeFabricClient.textureFor(dimension, tileX, tileZ, zoom);
                if (texture == null) {
                    graphics.fill(x, z, x + tileSize, z + tileSize, 0xff252c32);
                    graphics.centeredText(font, "불러오는 중", x + tileSize / 2, z + tileSize / 2, 0xffb8c2ca);
                } else {
                    graphics.blit(texture, x, z, tileSize, tileSize, 0f, 0f, 1f, 1f);
                }
            }
        }
        graphics.outline(left, top, left + tileSize * 3, top + tileSize * 3, 0xff59636c);
        if (minecraft.player != null && LedgeFabricClient.showPlayerMarker()) {
            int blocks = tileBlocks();
            double markerX = left + mapSize / 2.0 + (minecraft.player.getX() - (centerX * blocks + blocks / 2.0)) * mapSize / (blocks * 3.0);
            double markerZ = top + mapSize / 2.0 + (minecraft.player.getZ() - (centerZ * blocks + blocks / 2.0)) * mapSize / (blocks * 3.0);
            graphics.fill((int) markerX - 3, (int) markerZ - 3, (int) markerX + 4, (int) markerZ + 4, 0xffff5a5a);
        }
        for (var area : LocalMapAnnotations.areas()) {
            int x1 = mapX(left, mapSize, area.minX());
            int x2 = mapX(left, mapSize, area.maxX());
            int z1 = mapZ(top, mapSize, area.minZ());
            int z2 = mapZ(top, mapSize, area.maxZ());
            int rgb = Integer.parseInt(area.color().substring(1), 16);
            if (area.filled()) graphics.fill(x1, z1, x2, z2, 0x55000000 | rgb);
            graphics.outline(x1, z1, x2, z2, 0xff000000 | rgb);
            graphics.text(font, area.name(), x1 + 3, z1 + 3, 0xffffffff);
        }
        for (var marker : LocalMapAnnotations.markers()) {
            int x = mapX(left, mapSize, marker.x());
            int z = mapZ(top, mapSize, marker.z());
            int rgb = Integer.parseInt(marker.color().substring(1), 16);
            graphics.fill(x - 4, z - 4, x + 5, z + 5, 0xff000000 | rgb);
            graphics.text(font, marker.name(), x + 6, z - 5, 0xffffffff);
        }
        graphics.fill(10, 10, 190, coordinatesVisible ? 92 : 42, 0xd9000000);
        graphics.text(font, "좌표", 20, 18, 0xffffffff);
        graphics.text(font, "×", 172, 18, 0xffffffff);
        graphics.fill(width - 196, 10, width - 112, 36, 0xd9000000);
        graphics.text(font, LedgeFabricClient.showPlayerMarker() ? "● 플레이어" : "○ 플레이어", width - 190, 18, 0xffffffff);
        graphics.fill(width - 286, 10, width - 258, 36, 0xd9000000);
        graphics.centeredText(font, "−", width - 272, 18, 0xffffffff);
        graphics.fill(width - 252, 10, width - 224, 36, 0xd9000000);
        graphics.centeredText(font, "+", width - 238, 18, 0xffffffff);
        graphics.fill(width - 104, 10, width - 10, 36, 0xd9000000);
        graphics.text(font, printArmed ? "영역 선택 중" : "▣ 인쇄", width - 98, 18, 0xffffffff);
        if (coordinatesVisible && minecraft.player != null) {
            int worldX;
            int worldZ;
            var pos = minecraft.player.blockPosition();
            if (mouseX >= left && mouseX < left + tileSize * 3 && mouseY >= top && mouseY < top + tileSize * 3) {
                worldX = (centerX - 1) * tileBlocks() + (int) ((mouseX - left) * tileBlocks() * 3.0 / (tileSize * 3));
                worldZ = (centerZ - 1) * tileBlocks() + (int) ((mouseY - top) * tileBlocks() * 3.0 / (tileSize * 3));
            } else {
                worldX = pos.getX();
                worldZ = pos.getZ();
            }
            graphics.text(font, "X " + worldX, 20, 38, 0xffdce5eb);
            graphics.text(font, "Y " + pos.getY(), 20, 52, 0xffdce5eb);
            graphics.text(font, "Z " + worldZ, 20, 66, 0xffdce5eb);
        }
        graphics.centeredText(font, "줌 " + zoom + " · 드래그 이동 · 우클릭으로 포인트/영역 추가", width / 2, height - 18, 0xffb8c2ca);
        if (selectingPrint) {
            int sx = (int) Math.min(selectionStartX, selectionEndX);
            int sy = (int) Math.min(selectionStartY, selectionEndY);
            int ex = (int) Math.max(selectionStartX, selectionEndX);
            int ey = (int) Math.max(selectionStartY, selectionEndY);
            graphics.fill(sx, sy, ex, ey, 0x553c91e6);
            graphics.outline(sx, sy, ex, ey, 0xff66b1ff);
        }
        if (selectingArea) {
            int sx = (int) Math.min(selectionStartX, selectionEndX);
            int sy = (int) Math.min(selectionStartY, selectionEndY);
            int ex = (int) Math.max(selectionStartX, selectionEndX);
            int ey = (int) Math.max(selectionStartY, selectionEndY);
            graphics.fill(sx, sy, ex, ey, areaFilled ? 0x552196f3 : 0x221d9e74);
            graphics.outline(sx, sy, ex, ey, 0xff66b1ff);
        }
        if (contextMenuVisible) {
            graphics.fill(contextMenuX, contextMenuY, contextMenuX + 150, contextMenuY + 66, 0xf020252b);
            graphics.outline(contextMenuX, contextMenuY, contextMenuX + 150, contextMenuY + 66, 0xff69737d);
            graphics.text(font, "포인트 표시하기", contextMenuX + 8, contextMenuY + 6, 0xffffffff);
            graphics.text(font, "영역 채우기", contextMenuX + 8, contextMenuY + 27, 0xffffffff);
            graphics.text(font, "아웃라인 만들기", contextMenuX + 8, contextMenuY + 48, 0xffffffff);
        }
        if (annotationDialog) {
            int dx = (width - 280) / 2;
            int dy = (height - 118) / 2;
            graphics.fill(dx, dy, dx + 280, dy + 118, 0xf014181d);
            graphics.outline(dx, dy, dx + 280, dy + 118, 0xff77828d);
            graphics.text(font, dialogIsArea ? "영역 이름과 색상" : "포인트 이름과 배너 색상", dx + 12, dy + 10, 0xffffffff);
            graphics.text(font, "이름: " + dialogName + (colorFieldActive ? "" : "_"), dx + 12, dy + 38, 0xffdce5eb);
            graphics.text(font, "HEX: " + dialogColor + (colorFieldActive ? "_" : ""), dx + 12, dy + 62, 0xffdce5eb);
            graphics.text(font, "Tab으로 입력칸 전환 · Enter 저장 · Esc 취소", dx + 12, dy + 92, 0xffaab5be);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (annotationDialog) {
            int dy = (height - 118) / 2;
            if (y >= dy + 30 && y <= dy + 54) colorFieldActive = false;
            else if (y >= dy + 56 && y <= dy + 82) colorFieldActive = true;
            return true;
        }
        if (annotationDialog) {
            int dy = (height - 118) / 2;
            if (y >= dy + 30 && y <= dy + 54) colorFieldActive = false;
            else if (y >= dy + 56 && y <= dy + 82) colorFieldActive = true;
            return true;
        }
        if (x >= 160 && x <= 188 && y >= 10 && y <= 36) {
            coordinatesVisible = false;
            return true;
        }
        if (!coordinatesVisible && x >= 10 && x <= 190 && y >= 10 && y <= 44) {
            coordinatesVisible = true;
            return true;
        }
        if (x >= width - 196 && x <= width - 112 && y >= 10 && y <= 36) {
            LedgeFabricClient.togglePlayerMarker();
            return true;
        }
        if (y >= 10 && y <= 36 && x >= width - 286 && x <= width - 258) {
            changeZoom(-1);
            return true;
        }
        if (y >= 10 && y <= 36 && x >= width - 252 && x <= width - 224) {
            changeZoom(1);
            return true;
        }
        if (x >= width - 104 && x <= width - 10 && y >= 10 && y <= 36) {
            printArmed = true;
            return true;
        }
        if (event.button() == 1 && onMap(x, y)) {
            contextMenuVisible = true;
            contextMenuX = Math.min((int) x, width - 160);
            contextMenuY = Math.min((int) y, height - 76);
            int size = mapSize();
            contextWorldX = mapWorldX((width - size) / 2, size, (int) x);
            contextWorldZ = mapWorldZ((height - size) / 2, size, (int) y);
            return true;
        }
        if (contextMenuVisible && event.button() == 0) {
            int option = (int) ((y - contextMenuY) / 22);
            boolean selected = x >= contextMenuX && x <= contextMenuX + 150
                    && y >= contextMenuY && y <= contextMenuY + 66;
            contextMenuVisible = false;
            if (selected && option == 0) {
                dialogIsArea = false;
                dialogX = contextWorldX;
                dialogZ = contextWorldZ;
                openAnnotationDialog();
            } else if (selected && (option == 1 || option == 2)) {
                areaFilled = option == 1;
                awaitingArea = true;
            }
            return true;
        }
        if (event.button() == 0 && x > 20 && x < width - 20 && y > 20 && y < height - 20) {
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
                selectionStartX = selectionEndX = x;
                selectionStartY = selectionEndY = y;
                int size = mapSize();
                areaStartX = mapWorldX((width - size) / 2, size, (int) x);
                areaStartZ = mapWorldZ((height - size) / 2, size, (int) y);
                return true;
            }
            dragging = true;
            lastMouseX = x;
            lastMouseY = y;
            dragX = 0;
            dragY = 0;
            followPlayer = false;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (selectingArea) {
            selectionEndX = event.x();
            selectionEndY = event.y();
            return true;
        }
        if (selectingPrint) {
            selectionEndX = event.x();
            selectionEndY = event.y();
            return true;
        }
        if (!dragging) return super.mouseDragged(event, deltaX, deltaY);
        dragX += event.x() - lastMouseX;
        dragY += event.y() - lastMouseY;
        lastMouseX = event.x();
        lastMouseY = event.y();
        int mapSize = Math.min(height - 36, width - 300);
        int tileSize = Math.max(96, mapSize) / 3;
        int shiftX = (int) (dragX / tileSize);
        int shiftZ = (int) (dragY / tileSize);
        if (shiftX != 0 || shiftZ != 0) {
            centerX -= shiftX;
            centerZ -= shiftZ;
            dragX -= (double) shiftX * tileSize;
            dragY -= (double) shiftZ * tileSize;
            requestCurrentTiles();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (selectingArea && event.button() == 0) {
            selectingArea = false;
            int size = mapSize();
            areaEndX = mapWorldX((width - size) / 2, size, (int) event.x());
            areaEndZ = mapWorldZ((height - size) / 2, size, (int) event.y());
            if (areaStartX != areaEndX && areaStartZ != areaEndZ) {
                dialogIsArea = true;
                dialogMinX = Math.min(areaStartX, areaEndX);
                dialogMaxX = Math.max(areaStartX, areaEndX);
                dialogMinZ = Math.min(areaStartZ, areaEndZ);
                dialogMaxZ = Math.max(areaStartZ, areaEndZ);
                openAnnotationDialog();
            }
            return true;
        }
        if (selectingPrint && event.button() == 0) {
            selectionEndX = event.x();
            selectionEndY = event.y();
            selectingPrint = false;
            saveSelectedScreenshot();
            return true;
        }
        if (dragging && event.button() == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void saveSelectedScreenshot() {
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> {
            double scaleX = image.getWidth() / (double) Math.max(1, width);
            double scaleY = image.getHeight() / (double) Math.max(1, height);
            int left = clamp((int) (Math.min(selectionStartX, selectionEndX) * scaleX), 0, image.getWidth());
            int top = clamp((int) (Math.min(selectionStartY, selectionEndY) * scaleY), 0, image.getHeight());
            int right = clamp((int) (Math.max(selectionStartX, selectionEndX) * scaleX), 0, image.getWidth());
            int bottom = clamp((int) (Math.max(selectionStartY, selectionEndY) * scaleY), 0, image.getHeight());
            if (right - left < 8 || bottom - top < 8) {
                image.close();
                return;
            }
            NativeImage crop = new NativeImage(right - left, bottom - top, false);
            crop.copyRect(image, left, top, right - left, bottom - top, 0, 0, false, false);
            image.close();
            Path directory = minecraft.gameDirectory.toPath().resolve("screenshots");
            String name = "ledge-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png";
            try {
                Files.createDirectories(directory);
                crop.writeToFile(directory.resolve(name));
                if (minecraft.player != null) minecraft.player.sendOverlayMessage(Component.literal("영역 스크린샷 저장: screenshots/" + name));
            } catch (IOException exception) {
                if (minecraft.player != null) minecraft.player.sendOverlayMessage(Component.literal("스크린샷 저장 실패: " + exception.getMessage()));
            } finally {
                crop.close();
            }
        });
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontalAmount, double verticalAmount) {
        changeZoom(verticalAmount > 0 ? 1 : -1);
        return true;
    }

    private void changeZoom(int amount) {
        int nextZoom = Math.max(0, Math.min(12, zoom + amount));
        if (nextZoom != zoom) {
            zoom = nextZoom;
            followPlayer = true;
            requestVisibleTiles();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!annotationDialog) return super.keyPressed(event);
        if (event.key() == 257) {
            saveAnnotation();
            return true;
        }
        if (event.key() == 256) {
            annotationDialog = false;
            return true;
        }
        if (event.key() == 258) {
            colorFieldActive = !colorFieldActive;
            return true;
        }
        if (event.key() == 259) {
            if (colorFieldActive && !dialogColor.isEmpty()) dialogColor = dialogColor.substring(0, dialogColor.length() - 1);
            else if (!colorFieldActive && !dialogName.isEmpty()) dialogName = dialogName.substring(0, dialogName.length() - 1);
            return true;
        }
        return true;
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
    }

    private void saveAnnotation() {
        String name = dialogName.trim();
        if (name.isEmpty() || !dialogColor.matches("#[0-9a-fA-F]{6}")) {
            if (minecraft.player != null) minecraft.player.sendOverlayMessage(Component.literal("이름과 #RRGGBB 색상을 확인해 주세요."));
            return;
        }
        String color = dialogColor.toUpperCase(java.util.Locale.ROOT);
        if (dialogIsArea) {
            LocalMapAnnotations.addArea(new LocalMapAnnotations.Area(dialogMinX, dialogMinZ, dialogMaxX, dialogMaxZ,
                    name, color, areaFilled));
        } else {
            LocalMapAnnotations.addMarker(new LocalMapAnnotations.Marker(dialogX, dialogZ, name, color));
        }
        annotationDialog = false;
    }

    private void loadAnnotations(String targetDimension) {
        var server = minecraft.getCurrentServer();
        String serverId = server == null ? "singleplayer" : server.ip;
        LocalMapAnnotations.load(minecraft.gameDirectory.toPath(), serverId, targetDimension);
    }

    private int mapSize() {
        return Math.max(96, Math.min(height - 36, width - 300));
    }

    private boolean onMap(double x, double y) {
        int size = mapSize();
        int left = (width - size) / 2;
        int top = (height - size) / 2;
        return x >= left && x < left + size && y >= top && y < top + size;
    }

    private int mapWorldX(int left, int size, int x) {
        return (centerX - 1) * tileBlocks() + (int) ((x - left) * tileBlocks() * 3.0 / size);
    }

    private int mapWorldZ(int top, int size, int y) {
        return (centerZ - 1) * tileBlocks() + (int) ((y - top) * tileBlocks() * 3.0 / size);
    }

    private int mapX(int left, int size, int worldX) {
        return left + (int) ((worldX - (centerX - 1) * tileBlocks()) * size / (tileBlocks() * 3.0));
    }

    private int mapZ(int top, int size, int worldZ) {
        return top + (int) ((worldZ - (centerZ - 1) * tileBlocks()) * size / (tileBlocks() * 3.0));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        LedgeFabricClient.unsubscribeTiles();
    }
}
