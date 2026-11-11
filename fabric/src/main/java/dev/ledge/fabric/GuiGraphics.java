package dev.ledge.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * The subset of 1.20's GuiGraphics that the map screen uses, built on PoseStack + GuiComponent so the screen code
 * stays the same on 1.16 – 1.19.
 */
final class GuiGraphics {
    private final PoseStack pose;

    GuiGraphics(PoseStack pose) { this.pose = pose; }

    PoseStack pose() { return pose; }

    void fill(int x0, int y0, int x1, int y1, int color) { GuiComponent.fill(pose, x0, y0, x1, y1, color); }

    void drawString(Font font, String text, int x, int y, int color) { GuiComponent.drawString(pose, font, text, x, y, color); }

    void drawCenteredString(Font font, String text, int x, int y, int color) { GuiComponent.drawCenteredString(pose, font, text, x, y, color); }

    /** outline(x, y, width, height): one-pixel border. */
    void renderOutline(int x, int y, int width, int height, int color) {
        fill(x, y, x + width, y + 1, color);
        fill(x, y + height - 1, x + width, y + height, color);
        fill(x, y + 1, x + 1, y + height - 1, color);
        fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    void blit(ResourceLocation texture, int x, int y, int width, int height, float u, float v, int uWidth, int vHeight, int textureWidth, int textureHeight) {
        Minecraft.getInstance().getTextureManager().bind(texture);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        GuiComponent.blit(pose, x, y, width, height, u, v, uWidth, vHeight, textureWidth, textureHeight);
        RenderSystem.disableBlend();
    }

    /** GUI-space rectangle (already scaled by the caller); converted to framebuffer pixels here. */
    void enableScissor(int x0, int y0, int x1, int y1) {
        com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        RenderSystem.enableScissor((int) (x0 * scale), (int) (window.getHeight() - y1 * scale),
                Math.max(0, (int) ((x1 - x0) * scale)), Math.max(0, (int) ((y1 - y0) * scale)));
    }

    void disableScissor() { RenderSystem.disableScissor(); }
}
