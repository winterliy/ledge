package dev.ledge.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Quaternion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * The subset of 1.20's GuiGraphics that the map screen uses. Before 1.16 screens get no PoseStack, so drawing goes
 * through the static GuiComponent helpers and the transform through RenderSystem's matrix stack.
 */
final class GuiGraphics {
    private final Pose pose = new Pose();

    Pose pose() { return pose; }

    /** PoseStack look-alike backed by the fixed-function matrix stack. */
    static final class Pose {
        void pushPose() { RenderSystem.pushMatrix(); }
        void popPose() { RenderSystem.popMatrix(); }
        void translate(double x, double y, double z) { RenderSystem.translated(x, y, z); }
        void scale(float x, float y, float z) { RenderSystem.scalef(x, y, z); }
        void mulPose(Quaternion q) {
            float w = Math.max(-1f, Math.min(1f, q.r()));
            float angle = (float) (2.0 * Math.acos(w));
            float s = (float) Math.sqrt(1.0 - w * w);
            if (s < 1.0e-6f) return;
            RenderSystem.rotatef((float) Math.toDegrees(angle), q.i() / s, q.j() / s, q.k() / s);
        }
    }

    void fill(int x0, int y0, int x1, int y1, int color) { GuiComponent.fill(x0, y0, x1, y1, color); }

    void drawString(Font font, String text, int x, int y, int color) { font.drawShadow(text, x, y, color); }

    void drawCenteredString(Font font, String text, int x, int y, int color) { font.drawShadow(text, x - font.width(text) / 2f, y, color); }

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
        GuiComponent.blit(x, y, width, height, u, v, uWidth, vHeight, textureWidth, textureHeight);
        RenderSystem.disableBlend();
    }

    /** GUI-space rectangle (already scaled by the caller); converted to framebuffer pixels here. */
    void enableScissor(int x0, int y0, int x1, int y1) {
        com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x0 * scale), (int) (window.getHeight() - y1 * scale),
                Math.max(0, (int) ((x1 - x0) * scale)), Math.max(0, (int) ((y1 - y0) * scale)));
    }

    void disableScissor() { GL11.glDisable(GL11.GL_SCISSOR_TEST); }
}
