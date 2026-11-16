package com.thenathe.litelights.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
    @Shadow
    @Final
    private RenderTarget renderTarget;

    /** After the sky pass has closed (every return path), for LiteRenderEvents.AFTER_SKY. */
    @Inject(method = "render", at = @At("RETURN"))
    private void litelights$afterSky(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo callback) {
        LiteRenderEvents.AFTER_SKY.invoker().afterSky(renderTarget, LiteLightsRenderer.get().frame());
    }
}
