package com.thenathe.litelights.mixin.skyfog;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.thenathe.litelights.render.frame.ShownWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla's sun, moon and stars dim with the shown rain (the Dynamic clouds' pace), as the sky does. */
@Mixin(SkyRenderer.class)
abstract class SkyRainMixin {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float litelights$shownRain(ClientLevel level, float partialTicks, Operation<Float> original) {
        return ShownWeather.rain(level, partialTicks);
    }
}
