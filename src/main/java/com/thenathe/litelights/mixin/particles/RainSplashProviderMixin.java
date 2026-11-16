package com.thenathe.litelights.mixin.particles;

import com.thenathe.litelights.render.feature.weather.SoftRainSplash;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.WaterDropParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's rain splash bits (the {@code rain} particle, only ever made by this provider) drawn as
 * {@link SoftRainSplash} under Vanilla+ and Enhanced; it decides when vanilla's stay.
 */
@Mixin(WaterDropParticle.Provider.class)
abstract class RainSplashProviderMixin {
    @Inject(method = "createParticle(Lnet/minecraft/core/particles/SimpleParticleType;Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDDLnet/minecraft/util/RandomSource;)Lnet/minecraft/client/particle/Particle;",
        at = @At("HEAD"), cancellable = true)
    private void litelights$softSplash(SimpleParticleType options, ClientLevel level, double x, double y, double z,
                                       double xAux, double yAux, double zAux, RandomSource random,
                                       CallbackInfoReturnable<Particle> callback) {
        SoftRainSplash splash = SoftRainSplash.create(level, x, y, z, random);
        if (splash != null) {
            callback.setReturnValue(splash);
        }
    }
}
