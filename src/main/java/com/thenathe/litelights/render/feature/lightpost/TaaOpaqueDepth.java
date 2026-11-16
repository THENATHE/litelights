package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPipelines;
import com.thenathe.litelights.render.post.PostTexture;
import com.thenathe.litelights.render.post.PostTimings;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The depth of the opaque world, copied for TAA after the solid world is drawn and before
 * translucent surfaces (water, glass, ice, clouds, particles) write theirs. Where the two differ,
 * the pixel shows something through a surface, and TAA can follow what is behind it with its own
 * depth instead of the surface's. Two textures take turns, so last frame's copy is still there for
 * telling whether what is behind a surface was there a frame ago.
 *
 * <p>One full-screen pass into a 32-bit float texture, only on frames TAA runs.
 */
final class TaaOpaqueDepth implements AutoCloseable {
    static final RenderPipeline PIPELINE = PostPipelines.fullScreen("taa_opaque_depth", "taa_opaque_depth",
        PostPipelines.samplers("InDepth"), new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, PostPipelines.WRITE_RGBA), 0).build();

    private final PostTexture[] copies = {
        new PostTexture("TAA opaque depth A", GpuFormat.R32_FLOAT, 1),
        new PostTexture("TAA opaque depth B", GpuFormat.R32_FLOAT, 1)
    };
    private int current;
    private boolean takenThisFrame;
    private boolean previousValid;

    /** Copies the main target's depth as it is now. Call with no render pass open. */
    void take(RenderTarget mainTarget) {
        GpuTextureView depth = mainTarget.getDepthTextureView();
        if (depth == null) {
            return;
        }
        GpuTextureView target = copies[current].ensure(mainTarget.width, mainTarget.height);
        PostTimings.begin(PostTimings.TAA_OPAQUE_DEPTH);
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights TAA opaque depth", target, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsRenderer.get().bindWorldUniforms(pass);
            pass.setUniform("InDepth", depth, PostContext.nearestSampler());
            pass.draw(3, 1, 0, 0);
        }
        PostTimings.end(PostTimings.TAA_OPAQUE_DEPTH);
        takenThisFrame = true;
    }

    /** This frame's copy, if one was taken at this size. */
    @Nullable GpuTextureView thisFrame(int width, int height) {
        PostTexture copy = copies[current];
        return takenThisFrame && copy.width() == width && copy.height() == height ? copy.view() : null;
    }

    /** Last frame's copy, if it was taken at this size. */
    @Nullable GpuTextureView lastFrame(int width, int height) {
        PostTexture copy = copies[1 - current];
        return previousValid && copy.width() == width && copy.height() == height ? copy.view() : null;
    }

    /** The frame is done: this frame's copy becomes last frame's. */
    void endFrame() {
        previousValid = takenThisFrame;
        takenThisFrame = false;
        current = 1 - current;
    }

    /** Forgets both copies, for a frame TAA didn't run. */
    void forget() {
        previousValid = false;
        takenThisFrame = false;
    }

    @Override
    public void close() {
        for (PostTexture copy : copies) {
            copy.close();
        }
        forget();
    }
}
