package com.thenathe.litelights.mixin.skyfog;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.thenathe.litelights.render.feature.clouds.DynamicClouds;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The level's far plane (the larger of four times the render distance and vanilla's cloud range),
 * moved out while the Dynamic clouds draw so their farthest cube is in front of it whatever the
 * cloud range is set to. The far terrain module's mixin changes the same value; each takes the
 * larger, so the order doesn't matter.
 */
@Mixin(Camera.class)
abstract class CloudFarPlaneMixin {
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
    private float litelights$farPlaneForClouds(float farPlane) {
        return DynamicClouds.get().farPlane(farPlane);
    }
}
