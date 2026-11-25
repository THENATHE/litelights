package com.thenathe.litelights.mixin.particles;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.thenathe.litelights.render.feature.particles.FallingLeaves;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.FallingLeavesParticle;
import net.minecraft.client.particle.FallingParticle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's falling leaves (and cherry petals) drift with LiteLights' wind while Falling leaves is
 * on: each tick's move is handed to FallingLeaves, which adds the wind's carry and turns the petals'
 * fixed drift to the wind's heading. The particle's own velocity, and so its swirl, is vanilla's.
 */
@Mixin(FallingParticle.class)
abstract class FallingParticleMixin extends SingleQuadParticle {
    @Shadow
    @Final
    private boolean flowAway;

    private static final int FALLING_LIFETIME = 300;

    protected FallingParticleMixin(ClientLevel level, double x, double y, double z, TextureAtlasSprite sprite) {
        super(level, x, y, z, sprite);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/FallingParticle;move(DDD)V"))
    private void litelights$rideTheWind(FallingParticle particle, double moveX, double moveY, double moveZ, Operation<Void> move) {
        if (!FallingLeaves.steering() || !((Object) this instanceof FallingLeavesParticle)) {
            move.call(particle, moveX, moveY, moveZ);
            return;
        }
        // FallingParticle starts its lifetime at 300 and counts it down; tick has already taken this tick's.
        double[] steered = FallingLeaves.steer(x, z, moveX, moveY, moveZ, flowAway, FALLING_LIFETIME - lifetime);
        move.call(particle, steered[0], steered[1], steered[2]);
    }
}
