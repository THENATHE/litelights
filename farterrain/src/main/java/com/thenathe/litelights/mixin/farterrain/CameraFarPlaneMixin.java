package com.thenathe.litelights.mixin.farterrain;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.thenathe.litelights.render.feature.farterrain.FarTerrain;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The level's far plane, as the camera works it out each frame (the larger of four times the
 * render distance and the cloud range). Kept for LiteLights' own far terrain, which draws into the
 * level's depth and needs the far plane moved out to its distance (FarTerrain.farPlane, vanilla's
 * until then). Every consumer of the projection sees the same matrix.
 */
@Mixin(Camera.class)
abstract class CameraFarPlaneMixin {
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
    private float litelights$farPlaneForFarTerrain(float farPlane) {
        return FarTerrain.get().farPlane(farPlane);
    }
}
