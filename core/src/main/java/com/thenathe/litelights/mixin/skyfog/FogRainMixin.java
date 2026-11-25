package com.thenathe.litelights.mixin.skyfog;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.thenathe.litelights.render.frame.ShownWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's fog colour and rain fog follow the shown weather (the Dynamic clouds' pace), so where
 * vanilla's fog still shows (LiteLights' fog off) it closes in with the clouds.
 */
@Mixin(AtmosphericFogEnvironment.class)
abstract class FogRainMixin {
    @WrapOperation(method = {"getBaseColor", "updateRainFogState"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float litelights$shownRain(ClientLevel level, float partialTicks, Operation<Float> original) {
        return ShownWeather.rain(level, partialTicks);
    }

    @WrapOperation(method = "getBaseColor", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F"))
    private float litelights$shownThunder(ClientLevel level, float partialTicks, Operation<Float> original) {
        return ShownWeather.thunder(level, partialTicks);
    }
}
