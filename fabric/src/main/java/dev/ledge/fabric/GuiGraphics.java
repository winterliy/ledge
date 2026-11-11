package dev.ledge.fabric;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/** The subset of 1.20's GuiGraphics that the map screen uses, on 1.12's DrawableHelper and GlStateManager. */
final class GuiGraphics {
    private final Pose pose = new Pose();

    Pose pose() { return pose; }

    static final class Pose {
        void pushPose() { GlStateManager.pushMatrix(); }
        void popPose() { GlStateManager.popMatrix(); }
        void translate(double x, double y, double z) { GlStateManager.translate(x, y, z); }
        void scale(float x, float y, float z) { GlStateManager.scale(x, y, z); }
        void rotateZ(float degrees) { GlStateManager.rotate(degrees, 0f, 0f, 1f); }
    }

    void fill(int x0, int y0, int x1, int y1, int color) { DrawableHelper.fill(x0, y0, x1, y1, color); }

    void drawString(ModernScreen.Font font, String text, int x, int y, int color) { font.renderer.drawWithShadow(text, x, y, color); }

    void drawCenteredString(ModernScreen.Font font, String text, int x, int y, int color) { font.renderer.drawWithShadow(text, x - font.width(text) / 2f, y, color); }

    void renderOutline(int x, int y, int width, int height, int color) {
        fill(x, y, x + width, y + 1, color);
        fill(x, y + height - 1, x + width, y + height, color);
        fill(x, y + 1, x + 1, y + height - 1, color);
        fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    void blit(Identifier texture, int x, int y, int width, int height, float u, float v, int uWidth, int vHeight, int textureWidth, int textureHeight) {
        MinecraftClient.getInstance().getTextureManager().bindTexture(texture);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        DrawableHelper.drawTexture(x, y, u, v, uWidth, vHeight, width, height, textureWidth, textureHeight);
        GlStateManager.disableBlend();
    }

    void enableScissor(int x0, int y0, int x1, int y1) {
        int[] viewport = new int[16];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        MinecraftClient client = MinecraftClient.getInstance();
        double scale = client.currentScreen != null && client.currentScreen.width > 0 ? (double) viewport[2] / client.currentScreen.width : 1.0;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x0 * scale), (int) (viewport[3] - y1 * scale),
                Math.max(0, (int) ((x1 - x0) * scale)), Math.max(0, (int) ((y1 - y0) * scale)));
    }

    void disableScissor() { GL11.glDisable(GL11.GL_SCISSOR_TEST); }
}
