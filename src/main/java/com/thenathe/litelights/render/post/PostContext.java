package com.thenathe.litelights.render.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What a post pass gets: the main target, this frame's LiteFrame values and settings, and a few
 * shared helpers. One instance is reused for every pass of every frame.
 *
 * <p>The main target's colour is sRGB-encoded RGBA8; its depth is reversed (0 is far). During the
 * world stage, depth holds the level only. During the final stage it holds the hand (or the level
 * and the hand merged when a vanilla post effect is active), so final passes shouldn't rely on it.
 *
 * <p>Keep the main target's alpha as you found it during the world stage: it carries the
 * emissive mask that bloom reads (see docs/ARCHITECTURE.md). The tonemap writes alpha back to 1.
 */
public final class PostContext {
    private final PostTexture sceneCopy = new PostTexture("scene copy", GpuFormat.RGBA8_UNORM, 1);
    private RenderTarget mainTarget;
    private FrameState frame;
    private RenderSettings settings = RenderSettings.DEFAULTS;
    private PostSlot.Stage stage = PostSlot.Stage.WORLD;

    void begin(RenderTarget target, FrameState frameState, RenderSettings renderSettings, PostSlot.Stage currentStage) {
        mainTarget = target;
        frame = frameState;
        settings = renderSettings;
        stage = currentStage;
    }

    public RenderTarget mainTarget() {
        return mainTarget;
    }

    public int width() {
        return mainTarget.width;
    }

    public int height() {
        return mainTarget.height;
    }

    public GpuTextureView sceneColor() {
        return Objects.requireNonNull(mainTarget.getColorTextureView(), "main target has no colour");
    }

    public GpuTextureView sceneDepth() {
        return Objects.requireNonNull(mainTarget.getDepthTextureView(), "main target has no depth");
    }

    public FrameState frame() {
        return frame;
    }

    public RenderSettings settings() {
        return settings;
    }

    public PostSlot.Stage stage() {
        return stage;
    }

    /**
     * Copies the main target's colour as it is now into a shared full-resolution texture and returns
     * it, for a pass that reads the scene and writes the result back into the main target. Each
     * call copies again, so the copy always includes the passes before it.
     */
    public GpuTextureView copyOfScene() {
        GpuTextureView copy = sceneCopy.ensure(width(), height());
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
            Objects.requireNonNull(mainTarget.getColorTexture()), sceneCopy.texture(), 0, 0, 0, 0, 0, width(), height());
        return copy;
    }

    /**
     * Binds vanilla's default uniforms and LiteLights' uniforms with the world frame (sun, shadow
     * maps, matrices). Post passes run after the level, when the default binding would otherwise
     * give them the neutral frame.
     */
    public void bindUniforms(RenderPass pass) {
        RenderSystem.bindDefaultUniforms(pass);
        LiteLightsRenderer.get().bindWorldUniforms(pass);
    }

    /**
     * Opens a pass that draws a full-screen pipeline into {@code target}, with the pipeline set and
     * vanilla's and LiteLights' uniforms bound (see {@link #bindUniforms}). The caller binds its
     * samplers and push constants, draws with {@code draw(3, 1, 0, 0)} and closes the pass.
     */
    public RenderPass openFullScreen(Supplier<String> label, RenderPipeline pipeline, GpuTextureView target) {
        RenderPass pass = GuardedRenderPass.open(label, target, Optional.empty());
        try {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            bindUniforms(pass);
        } catch (RuntimeException failure) {
            pass.close();
            throw failure;
        }
        return pass;
    }

    public static GpuSampler linearSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    public static GpuSampler nearestSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    }
}
