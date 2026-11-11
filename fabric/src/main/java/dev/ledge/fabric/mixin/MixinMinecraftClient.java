package dev.ledge.fabric.mixin;

import dev.ledge.fabric.LedgeFabricClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** End-of-tick hook (1.13 has no Fabric lifecycle API). */
@Mixin(MinecraftClient.class)
public abstract class MixinMinecraftClient {
    @Inject(method = "tick", at = @At("TAIL"))
    private void ledge$tick(CallbackInfo ci) { LedgeFabricClient.onEndTick((MinecraftClient) (Object) this); }
}
