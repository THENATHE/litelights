package com.thenathe.litelights.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import com.thenathe.litelights.render.pipeline.PipelineVariants;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * If a pipeline using a LiteLights shader fails to compile, reports it on the settings row of the
 * feature that owns the pipeline ({@link PipelineOwners}). Where the shader stands in for vanilla's
 * or Sodium's, the pipeline is compiled again with the original shader, so a shader problem (a
 * driver quirk, a resource pack replacing an include) costs LiteLights' shading on that pipeline
 * and never the game. LiteLights' own pipelines (post passes, shadow casters) have no original;
 * their feature checks for a missing pipeline and stays off. Either way the feature is tried again
 * ({@link RenderFeatureStatus#compileFailed}), and every resource reload starts afresh.
 */
@Mixin(FrontendGpuDevice.class)
abstract class FrontendGpuDeviceMixin {
    @ModifyReturnValue(method = "compilePipeline", at = @At("RETURN"))
    private CompletableFuture<CompiledRenderPipeline.Pending> litelights$fallBackToOriginalShaders(
        CompletableFuture<CompiledRenderPipeline.Pending> compiled,
        RenderPipeline pipeline,
        ShaderSource shaderSource,
        Executor executor
    ) {
        return compiled.thenCompose(pending -> {
            if (pending != CompiledRenderPipeline.Pending.NULL) {
                return CompletableFuture.completedFuture(pending);
            }
            RenderFeature owner = PipelineOwners.ownerOf(pipeline);
            if (owner == null) {
                return CompletableFuture.completedFuture(pending);
            }
            RenderPipeline fallback = PipelineVariants.fallbackFor(pipeline);
            RenderFeatureStatus.compileFailed(owner, "A LiteLights shader failed to compile (" + pipeline.getLocation() + "); see the log",
                fallback != null);
            if (fallback == null) {
                LiteLightsClient.LOG.error("Shader for {} failed to compile; {} is off", pipeline.getLocation(), owner);
                return CompletableFuture.completedFuture(pending);
            }
            LiteLightsClient.LOG.error("Shader for {} failed to compile; using the original shader", pipeline.getLocation());
            return ((FrontendGpuDevice) (Object) this).compilePipeline(fallback, shaderSource, executor);
        });
    }
}
