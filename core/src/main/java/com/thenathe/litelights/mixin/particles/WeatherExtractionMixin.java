package com.thenathe.litelights.mixin.particles;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.thenathe.litelights.render.feature.weather.WeatherParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * With Custom weather visuals, LiteLights draws rain and snow as its own particles, so vanilla's
 * rain and snow sheets are left out of the frame. The weather state was already reset for this
 * frame, so skipping the extraction leaves it empty and vanilla draws nothing. Rain sounds and
 * vanilla's splash particles are untouched.
 */
@Mixin(LevelExtractor.class)
abstract class WeatherExtractionMixin {
    @WrapWithCondition(method = "extract", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;extractRenderState(Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V"))
    private boolean litelights$keepVanillaWeather(WeatherEffectRenderer renderer, ClientLevel level, float partialTicks, Vec3 camera,
                                                 WeatherRenderState state) {
        return !WeatherParticles.replacesVanillaWeather();
    }
}
