package com.thenathe.litelights.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.thenathe.litelights.render.pipeline.FilteringRenderPass;
import com.thenathe.litelights.render.pipeline.PipelineVariants;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A render pass that turns the game's own entity draws into shadow-caster draws. Entities are
 * drawn by replaying the frame's prepared feature groups into this pass: every pipeline they set is
 * swapped for its depth-only variant, and draws with pipelines that cast no shadow (name tags, blob
 * shadows, beams) are dropped. Uniforms LiteLights sets for the shadow view (the light's
 * projection) can't be overwritten by the replayed draws.
 */
final class ShadowCasterPass extends FilteringRenderPass {
    private final Set<String> lockedUniforms = new HashSet<>();

    /** Starts replaying into a pass, with no uniform locked; one instance serves every frame. */
    void start(RenderPass delegate) {
        begin(delegate);
        lockedUniforms.clear();
    }

    /** Sets a uniform that the replayed draws may not change. */
    void lockUniform(String name, GpuBufferSlice value) {
        target().setUniform(name, value);
        lockedUniforms.add(name);
    }

    @Override
    protected @Nullable CompiledRenderPipeline replacementFor(CompiledRenderPipeline pipeline) {
        if (!(pipeline instanceof FrontendRenderPipeline frontend)) {
            return null;
        }
        RenderPipeline source = PipelineVariants.sourceOf(frontend);
        RenderPipeline variant = source == null ? null : PipelineVariants.casterVariant(source);
        return variant == null ? null : RenderSystem.getCompiledPipelineNullable(variant);
    }

    @Override
    public void setUniform(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
        if (!lockedUniforms.contains(name)) {
            super.setUniform(name, textureView, sampler);
        }
    }

    @Override
    public void setUniform(String name, GpuBuffer value) {
        if (!lockedUniforms.contains(name)) {
            super.setUniform(name, value);
        }
    }

    @Override
    public void setUniform(String name, GpuBufferSlice value) {
        if (!lockedUniforms.contains(name)) {
            super.setUniform(name, value);
        }
    }

    @Override
    public void close() {
        target().close();
    }
}
