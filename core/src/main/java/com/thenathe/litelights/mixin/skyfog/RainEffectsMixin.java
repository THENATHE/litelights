package com.thenathe.litelights.mixin.skyfog;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.thenathe.litelights.render.frame.ShownWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The client's rain splashes on the ground and the sound of the rain follow the shown weather, so
 * with the Dynamic clouds the rain is heard as it is seen.
 */
@Mixin(ClientLevel.class)
abstract class RainEffectsMixin {
    @WrapOperation(method = "tickWeatherEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float litelights$shownRain(ClientLevel level, float partialTicks, Operation<Float> original) {
        return ShownWeather.rain(level, partialTicks);
    }
}
