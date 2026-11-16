package com.thenathe.litelights.mixin.farterrain;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.thenathe.litelights.render.feature.farterrain.FarTerrain;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The level's far plane, as the camera works it out each frame (the larger of four times the
 * render distance and the cloud range). While LiteLights shades Voxy's far terrain it moves past
 * Voxy's own, so the depth Voxy writes for its terrain isn't clamped at vanilla's far plane
 * (FarTerrain). Every consumer of the projection sees the same matrix, Voxy included.
 */
@Mixin(Camera.class)
abstract class CameraFarPlaneMixin {
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
    private float litelights$farPlaneForFarTerrain(float farPlane) {
        return FarTerrain.get().farPlane(farPlane);
    }
}
