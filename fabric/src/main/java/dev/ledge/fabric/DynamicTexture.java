package dev.ledge.fabric;

import net.minecraft.client.texture.NativeImageBackedTexture;

/** NativeImage-backed texture as in the newer API (1.12 uploads a BufferedImage). */
final class DynamicTexture extends NativeImageBackedTexture {
    DynamicTexture(NativeImage image) { super(image.image); }
}
