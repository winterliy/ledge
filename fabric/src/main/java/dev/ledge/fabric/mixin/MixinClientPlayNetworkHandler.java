package dev.ledge.fabric.mixin;

import dev.ledge.fabric.LedgeFabricClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Receives ledge:map plugin messages (1.13 has no Fabric networking API). Runs on the network thread. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class MixinClientPlayNetworkHandler {
    @Inject(method = "onCustomPayload", at = @At("HEAD"), cancellable = true)
    private void ledge$onCustomPayload(CustomPayloadS2CPacket packet, CallbackInfo ci) {
        if (!"ledge:map".equals(String.valueOf(packet.method_7733()))) return;
        net.minecraft.util.PacketByteBuf buffer = packet.getPayload();
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        LedgeFabricClient.onPayload(bytes);
        ci.cancel();
    }
}
