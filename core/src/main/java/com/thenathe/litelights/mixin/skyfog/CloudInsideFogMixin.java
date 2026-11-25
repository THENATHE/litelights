package com.thenathe.litelights.mixin.skyfog;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.thenathe.litelights.render.feature.clouds.DynamicClouds;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's fog for the frame, where it still shows (LiteLights' fog set to vanilla's): inside a
 * Dynamic cloud it closes in to the cloud's colour, as LiteLights' own fog does
 * (DynamicClouds.adjustVanillaFog). Anywhere else, untouched.
 */
@Mixin(FogRenderer.class)
abstract class CloudInsideFogMixin {
    @ModifyReturnValue(method = "setupFog", at = @At("RETURN"))
    private FogData litelights$insideCloudFog(FogData fog) {
        DynamicClouds.get().adjustVanillaFog(fog);
        return fog;
    }
}
