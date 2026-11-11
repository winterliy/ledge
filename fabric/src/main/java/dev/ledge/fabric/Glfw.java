package dev.ledge.fabric;

/** GLFW constants the map screen uses (1.13 is on LWJGL 3, so key codes already are GLFW codes). */
final class Glfw {
    private Glfw() { }

    static final int GLFW_KEY_ESCAPE = org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, GLFW_KEY_ENTER = org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
    static final int GLFW_KEY_TAB = org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, GLFW_KEY_BACKSPACE = org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
    static final int GLFW_KEY_KP_ENTER = org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER, GLFW_KEY_KP_ADD = org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD;
    static final int GLFW_KEY_KP_SUBTRACT = org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT, GLFW_KEY_EQUAL = org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL;
    static final int GLFW_KEY_MINUS = org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS, GLFW_KEY_Z = org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
    static final int GLFW_MOUSE_BUTTON_LEFT = 0, GLFW_MOUSE_BUTTON_RIGHT = 1;
    static final int GLFW_MOD_SHIFT = 1, GLFW_MOD_CONTROL = 2, GLFW_MOD_ALT = 4, GLFW_MOD_SUPER = 8;
}
