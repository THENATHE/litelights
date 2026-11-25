package com.thenathe.litelights.mixin.render;

import com.thenathe.litelights.render.LiteLightsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void litelights$frameStarting(CallbackInfo callback) {
        LiteLightsRenderer.get().frameStarting();
    }

    /** The world projection seam (see WorldProjection): the level's projection on its way to the GPU. */
    @ModifyArg(
        method = "renderLevel",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private Matrix4f litelights$worldProjection(Matrix4f projection) {
        return LiteLightsRenderer.get().projection().adjust(projection);
    }

    @Inject(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
    private void litelights$beforeLevel(CallbackInfo callback) {
        LiteLightsRenderer.get().beforeLevel((GameRenderer) (Object) this);
    }

    @Inject(
        method = "renderLevel",
        at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
    private void litelights$afterLevel(CallbackInfo callback) {
        LiteLightsRenderer.get().afterLevel((GameRenderer) (Object) this);
    }
}
