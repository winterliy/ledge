package dev.ledge.fabric;

import org.lwjgl.input.Keyboard;

/**
 * GLFW key/mouse constants the map screen was written against. Minecraft 1.12 uses LWJGL 2, so ModernScreen
 * translates LWJGL 2 key codes into these values before calling the screen's key handlers.
 */
final class Glfw {
    private Glfw() { }

    static final int GLFW_KEY_ESCAPE = 256, GLFW_KEY_ENTER = 257, GLFW_KEY_TAB = 258, GLFW_KEY_BACKSPACE = 259;
    static final int GLFW_KEY_KP_ENTER = 335, GLFW_KEY_KP_ADD = 334, GLFW_KEY_KP_SUBTRACT = 333;
    static final int GLFW_KEY_EQUAL = 61, GLFW_KEY_MINUS = 45, GLFW_KEY_Z = 90;
    static final int GLFW_KEY_LEFT = 263, GLFW_KEY_RIGHT = 262, GLFW_KEY_UP = 265, GLFW_KEY_DOWN = 264;
    static final int GLFW_MOUSE_BUTTON_LEFT = 0, GLFW_MOUSE_BUTTON_RIGHT = 1;
    static final int GLFW_MOD_SHIFT = 1, GLFW_MOD_CONTROL = 2, GLFW_MOD_ALT = 4, GLFW_MOD_SUPER = 8;

    /** LWJGL 2 key code -> GLFW key code (letters and digits map to their ASCII value). */
    static int fromLwjgl2(int code) {
        switch (code) {
            case Keyboard.KEY_ESCAPE: return GLFW_KEY_ESCAPE;
            case Keyboard.KEY_RETURN: return GLFW_KEY_ENTER;
            case Keyboard.KEY_NUMPADENTER: return GLFW_KEY_KP_ENTER;
            case Keyboard.KEY_TAB: return GLFW_KEY_TAB;
            case Keyboard.KEY_BACK: return GLFW_KEY_BACKSPACE;
            case Keyboard.KEY_EQUALS: return GLFW_KEY_EQUAL;
            case Keyboard.KEY_ADD: return GLFW_KEY_KP_ADD;
            case Keyboard.KEY_MINUS: return GLFW_KEY_MINUS;
            case Keyboard.KEY_SUBTRACT: return GLFW_KEY_KP_SUBTRACT;
            case Keyboard.KEY_LEFT: return GLFW_KEY_LEFT;
            case Keyboard.KEY_RIGHT: return GLFW_KEY_RIGHT;
            case Keyboard.KEY_UP: return GLFW_KEY_UP;
            case Keyboard.KEY_DOWN: return GLFW_KEY_DOWN;
            default:
                String name = Keyboard.getKeyName(code);
                return name != null && name.length() == 1 ? Character.toUpperCase(name.charAt(0)) : -1;
        }
    }

    static int modifiers() {
        int mods = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) mods |= GLFW_MOD_SHIFT;
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) mods |= GLFW_MOD_CONTROL;
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) mods |= GLFW_MOD_ALT;
        if (Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA)) mods |= GLFW_MOD_SUPER;
        return mods;
    }
}
