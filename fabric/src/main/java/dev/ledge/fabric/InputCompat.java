package dev.ledge.fabric;

import org.lwjgl.glfw.GLFW;

/** Before 1.21.9 screens receive raw (x, y, button) / (key, scancode, modifiers) values; these mirror the newer event objects. */
final class InputCompat {
    private InputCompat() { }

    record MouseButtonEvent(double x, double y, int button) {
        int buttonInfo() { return button; }
    }

    record KeyEvent(int key, int scancode, int modifiers) {
        boolean hasControlDown() { return (modifiers & GLFW.GLFW_MOD_CONTROL) != 0; }
        /** Command on macOS, Control elsewhere (what the newer helper does). */
        boolean hasControlDownWithQuirk() { return net.minecraft.client.gui.screens.Screen.hasControlDown(); }
    }

    record CharacterEvent(char character, int modifiers) {
        String codepointAsString() { return String.valueOf(character); }
        boolean isAllowedChatCharacter() { return net.minecraft.SharedConstants.isAllowedChatCharacter(character); }
    }
}
