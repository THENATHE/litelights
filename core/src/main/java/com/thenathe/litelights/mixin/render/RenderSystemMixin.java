package com.thenathe.litelights.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.feature.particles.HazeDepth;
import com.thenathe.litelights.render.feature.water.WaterRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla binds its default uniforms (projection, fog, globals, lighting) on every pass that draws
 * world or GUI geometry, and Sodium draws into those same passes. Binding LiteLights' uniforms at the
 * same moment reaches all of them with one hook and no Sodium internals. Water's own uniform block
 * rides along the same way, and so does the haze's scene distance, which only the haze pipelines name.
 */
@Mixin(RenderSystem.class)
abstract class RenderSystemMixin {
    @Inject(method = "initRenderer", at = @At("TAIL"))
    private static void litelights$createResources(GpuDevice device, CallbackInfo callback) {
        LiteLightsRenderer.get().createDeviceResources();
        WaterRenderer.get().createDeviceResources();
        HazeDepth.get().createDeviceResources();
    }

    @Inject(method = "bindDefaultUniforms", at = @At("TAIL"))
    private static void litelights$bindLiteUniforms(RenderPass renderPass, CallbackInfo callback) {
        LiteLightsRenderer.get().bindUniforms(renderPass);
        WaterRenderer.get().bind(renderPass);
        HazeDepth.get().bind(renderPass);
    }
}
