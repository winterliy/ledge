package dev.ledge.fabric;

import net.minecraft.util.Identifier;

/** One Ledge plugin-message frame (before 1.20.5 there are no typed payloads, only the raw "ledge:map" channel). */
@com.github.bsideup.jabel.Desugar
record MapPayload(byte[] bytes) {
    static final int MAX_PACKET_BYTES = 30_000;
    static final String CHANNEL = "ledge:map";

    MapPayload {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Invalid Ledge packet size");
        }
    }

    net.minecraft.util.PacketByteBuf buffer() {
        net.minecraft.util.PacketByteBuf buffer = new net.minecraft.util.PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeBytes(bytes);
        return buffer;
    }
}
