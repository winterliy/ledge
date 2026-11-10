package dev.ledge.fabric;

import net.minecraft.resources.ResourceLocation;

/** One Ledge plugin-message frame (before 1.20.5 there are no typed payloads, only the raw "ledge:map" channel). */
record MapPayload(byte[] bytes) {
    static final int MAX_PACKET_BYTES = 30_000;
    static final ResourceLocation CHANNEL = new ResourceLocation("ledge", "map");

    MapPayload {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Invalid Ledge packet size");
        }
    }

    net.minecraft.network.FriendlyByteBuf buffer() {
        net.minecraft.network.FriendlyByteBuf buffer = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        buffer.writeBytes(bytes);
        return buffer;
    }
}
