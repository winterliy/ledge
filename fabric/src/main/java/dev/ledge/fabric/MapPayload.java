package dev.ledge.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record MapPayload(byte[] bytes) implements CustomPacketPayload {
    static final int MAX_PACKET_BYTES = 30_000;
    static final Type<MapPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ledge", "map"));
    static final StreamCodec<RegistryFriendlyByteBuf, MapPayload> CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeBytes(value.bytes()),
            buffer -> {
                int length = buffer.readableBytes();
                if (length <= 0 || length > MAX_PACKET_BYTES) throw new IllegalArgumentException("Invalid Ledge packet size");
                byte[] bytes = new byte[length];
                buffer.readBytes(bytes);
                return new MapPayload(bytes);
            }
    );

    MapPayload {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Invalid Ledge packet size");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
