package com.thenathe.litelights.mixin.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.thenathe.litelights.render.compat.NoOitRenderTypes;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A render type without Improved Transparency pipelines is left out of the OIT stages instead of
 * throwing, and drawn after the OIT composite (see NoOitRenderTypes).
 */
@Mixin(PreparedRenderType.class)
abstract class PreparedRenderTypeMixin {
    @Shadow
    @Final
    private String name;

    @Shadow
    @Final
    private @Nullable OitPipelineSet oitPipelineSet;

    @Inject(method = "drawFromBufferOit", at = @At("HEAD"), cancellable = true)
    private void litelights$skipWithoutOitPipelines(StagedVertexBuffer.ExecuteInfo info, OitStage stage, RenderPass renderPass, CallbackInfo callback) {
        if (oitPipelineSet == null) {
            NoOitRenderTypes.skippedOitDraw(name);
            callback.cancel();
        }
    }

    @Inject(method = "drawFromBuffer", at = @At("HEAD"), cancellable = true)
    private void litelights$onlyUnsortedAfterComposite(StagedVertexBuffer.ExecuteInfo info, RenderPass renderPass, CallbackInfo callback) {
        if (NoOitRenderTypes.skipOrdinaryDraw(oitPipelineSet != null)) {
            callback.cancel();
        }
    }

    @Inject(method = "drawFromBuffer", at = @At("RETURN"))
    private void litelights$ordinaryDrawDone(StagedVertexBuffer.ExecuteInfo info, RenderPass renderPass, CallbackInfo callback) {
        NoOitRenderTypes.ordinaryDrawDone();
    }
}
