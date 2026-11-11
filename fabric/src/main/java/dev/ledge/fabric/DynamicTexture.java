package dev.ledge.fabric;

import net.minecraft.client.texture.NativeImageBackedTexture;

/** NativeImage-backed texture as in the newer API (wraps 1.13's NativeImage). */
final class DynamicTexture extends NativeImageBackedTexture {
    DynamicTexture(NativeImage image) { super(image.image); }
}
