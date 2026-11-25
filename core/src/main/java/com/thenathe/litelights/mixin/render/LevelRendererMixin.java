package com.thenathe.litelights.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.compat.NoOitRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    /**
     * The frame's entity draws are prepared and no render pass is open yet: the moment to replay
     * them into the mob shadow map, before the main pass reads it.
     */
    @ModifyExpressionValue(
        method = "render",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
    private FeatureRenderDispatcher.PreparedFrame litelights$drawMobShadows(FeatureRenderDispatcher.PreparedFrame frame) {
        LiteLightsRenderer.get().onFeaturesPrepared(frame);
        return frame;
    }

    /** The OIT result is composited into the main target: draw what the OIT stages had to leave out. */
    @Inject(method = "executeOit", at = @At("TAIL"))
    private void litelights$drawWithoutOit(ChunkSectionsToRender chunkSectionsToRender, FeatureRenderDispatcher.PreparedFrame featureFrame,
                                           CallbackInfo callback) {
        NoOitRenderTypes.drawSkipped(featureFrame);
    }
}
