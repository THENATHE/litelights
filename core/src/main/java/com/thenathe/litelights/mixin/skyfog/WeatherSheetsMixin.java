package com.thenathe.litelights.mixin.skyfog;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.thenathe.litelights.render.frame.ShownWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's rain and snow sheets (drawn when LiteLights' own rain isn't) fall as hard as the shown
 * weather: with the Dynamic clouds they ease in under the closing clouds like LiteLights' own.
 */
@Mixin(WeatherEffectRenderer.class)
abstract class WeatherSheetsMixin {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float litelights$shownRain(ClientLevel level, float partialTicks, Operation<Float> original) {
        return ShownWeather.rain(level, partialTicks);
    }
}
