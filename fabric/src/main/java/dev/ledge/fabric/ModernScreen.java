package dev.ledge.fabric;


import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.input.Mouse;

/**
 * Gives a 1.12 (LWJGL 2) screen the event methods of the 1.14+ screen API: double mouse coordinates, GLFW key codes,
 * charTyped and mouseScrolled, so the map screen code is shared with the newer branches.
 */
abstract class ModernScreen extends Screen {
    protected MinecraftClient minecraft;
    protected Font font;
    private double lastDragX, lastDragY;

    @Override
    public void init(MinecraftClient client, int width, int height) {
        this.minecraft = client;
        this.font = new Font(client.textRenderer);
        super.init(client, width, height);
    }

    public boolean mouseClicked(double x, double y, int button) { return false; }
    public boolean mouseReleased(double x, double y, int button) { return false; }
    public boolean mouseDragged(double x, double y, int button, double deltaX, double deltaY) { return false; }
    public boolean mouseScrolled(double x, double y, double amount) { return false; }
    public boolean charTyped(char character, int modifiers) { return false; }
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == Glfw.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return false;
    }
    public boolean isPauseScreen() { return true; }
    public void onClose() { minecraft.setScreen(null); }

    @Override public boolean shouldPauseGame() { return isPauseScreen(); }

    @Override protected void mouseClicked(int x, int y, int button) {
        lastDragX = x; lastDragY = y;
        mouseClicked((double) x, (double) y, button);
    }

    @Override protected void mouseReleased(int x, int y, int button) { mouseReleased((double) x, (double) y, button); }

    @Override protected void mouseDragged(int x, int y, int button, long time) {
        double dx = x - lastDragX, dy = y - lastDragY;
        lastDragX = x; lastDragY = y;
        mouseDragged((double) x, (double) y, button, dx, dy);
    }

    @Override public void handleMouse() {
        super.handleMouse();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            double x = Mouse.getEventX() * (double) width / minecraft.width;
            double y = height - Mouse.getEventY() * (double) height / minecraft.height - 1;
            mouseScrolled(x, y, Math.signum(wheel));
        }
    }

    @Override protected void keyPressed(char character, int code) {
        int key = Glfw.fromLwjgl2(code);
        int mods = Glfw.modifiers();
        boolean handled = key != -1 && keyPressed(key, code, mods);
        if (!handled && character >= ' ' && character != 127) charTyped(character, mods);
    }

    /** Mojmap-style font wrapper over the 1.12 TextRenderer. */
    static final class Font {
        final net.minecraft.client.font.TextRenderer renderer;
        final int lineHeight;
        Font(net.minecraft.client.font.TextRenderer renderer) { this.renderer = renderer; this.lineHeight = renderer.fontHeight; }
        int width(String text) { return renderer.getStringWidth(text); }
    }
}
