package com.thenathe.litelights.mixin.particles;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.thenathe.litelights.render.feature.particles.ParticleSystem;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The level's far plane (the larger of four times the render distance and vanilla's cloud range),
 * moved out past tonight's distant fireworks while a show is on, so its bursts, a kilometre and a
 * half to three kilometres off, are in front of it. The clouds' and far terrain's mixins change the
 * same value; each takes the larger, so the order doesn't matter.
 */
@Mixin(Camera.class)
abstract class SkyEventsFarPlaneMixin {
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
    private float litelights$farPlaneForFireworks(float farPlane) {
        return ParticleSystem.get().sky().farPlane(farPlane);
    }
}
