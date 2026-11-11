package dev.ledge.fabric;

import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.util.Identifier;
import net.minecraft.util.PacketByteBuf;

/** Plugin-message plumbing for 1.13 without Fabric API: the raw ledge:map channel plus minecraft:register. */
final class Net {
    private Net() { }

    static final Identifier CHANNEL = new Identifier("ledge", "map");
    private static ClientPlayNetworkHandler registeredOn;

    static boolean canSend() { return MinecraftClient.getInstance().getNetworkHandler() != null; }

    static void send(PacketByteBuf buffer) {
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler != null) handler.sendPacket(new CustomPayloadC2SPacket(CHANNEL, buffer));
    }

    /** Bukkit only forwards plugin messages on channels the client registered, so announce ledge:map once per connection. */
    static void registerChannel() {
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler == null || handler == registeredOn) return;
        registeredOn = handler;
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
        buffer.writeBytes(CHANNEL.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        handler.sendPacket(new CustomPayloadC2SPacket(new Identifier("minecraft", "register"), buffer));
    }
}
