package dev.ledge.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.opengl.GL11;

/** 1.13 screens already have the newer event methods; this adds the Mojang-named fields and a positioned scroll. */
abstract class ModernScreen extends Screen {
    protected MinecraftClient minecraft;
    protected Font font;

    @Override
    public void init(MinecraftClient client, int width, int height) {
        this.minecraft = client;
        this.font = new Font(client.textRenderer);
        super.init(client, width, height);
    }

    public boolean mouseScrolled(double x, double y, double amount) { return false; }

    /** 1.13 passes only the wheel amount; the cursor position comes from the mouse helper (framebuffer pixels). */
    @Override
    public boolean mouseScrolled(double amount) {
        int[] viewport = new int[16];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        double scale = viewport[2] > 0 ? (double) width / viewport[2] : 1.0;
        return mouseScrolled(minecraft.field_19945.method_18249() * scale, minecraft.field_19945.method_18250() * scale, amount);
    }

    public boolean isPauseScreen() { return true; }
    public void onClose() { minecraft.setScreen(null); }
    @Override public boolean shouldPauseGame() { return isPauseScreen(); }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == Glfw.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }

    /** Mojmap-style font wrapper over the TextRenderer. */
    static final class Font {
        final net.minecraft.client.font.TextRenderer renderer;
        final int lineHeight;
        Font(net.minecraft.client.font.TextRenderer renderer) { this.renderer = renderer; this.lineHeight = renderer.fontHeight; }
        int width(String text) { return renderer.getStringWidth(text); }
    }
}
