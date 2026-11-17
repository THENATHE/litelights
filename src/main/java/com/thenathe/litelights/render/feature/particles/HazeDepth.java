package com.thenathe.litelights.render.feature.particles;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import com.thenathe.litelights.render.post.PostPipelines;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * How far away the opaque scene is at each pixel, for the haze puffs and billboards (fog097): one
 * half-float channel holding the distance from the eye in blocks, copied from the main depth after
 * the solid world is drawn and before translucent terrain (from mixin.water.LevelRendererWaterMixin,
 * the same moment water copies the scene). Only on frames that draw haze; a 1 x 1 placeholder
 * otherwise, which the shaders read as "nothing behind".
 *
 * <p>Its own copy rather than water's scene copy: that one is only taken while water or ice has
 * reflections to trace, and the water shaders read its presence as a sign to trace them. This one
 * is one full-screen pass into a 2-byte texture, about 0.03 ms at 1080p.
 *
 * <p>Only the haze pipelines name its sampler, so it costs a texture unit nowhere else (the soft
 * particle layer uses 5 of OpenGL's 12 with Improved Transparency, 6 with this).
 */
public final class HazeDepth implements AutoCloseable {
    public static final String SAMPLER = "LiteHazeDepth";
    static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
        .withUniform(SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
        .build();

    private static final RenderPipeline PIPELINE = PostPipelines.fullScreen("haze_depth", LiteLightsClient.id("particle/haze_depth"),
            PostPipelines.samplers("InDepth"), new ColorTargetState(Optional.empty(), GpuFormat.R16_FLOAT, PostPipelines.WRITE_RGBA), 0)
        .build();

    private static final HazeDepth INSTANCE = new HazeDepth();

    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private @Nullable GpuTexture placeholder;
    private @Nullable GpuTextureView placeholderView;
    private boolean wanted;
    private boolean taken;

    private HazeDepth() {
    }

    public static HazeDepth get() {
        return INSTANCE;
    }

    static void claimPipelines() {
        PipelineOwners.claim(RenderFeature.PARTICLES, PIPELINE);
    }

    /** A new frame: no copy yet; {@link #want} says whether this one draws haze. */
    void beginFrame() {
        wanted = false;
        taken = false;
    }

    /** This frame's particle batch has haze that reads the scene's distance. */
    void want() {
        wanted = true;
    }

    public boolean wanted() {
        return wanted && RenderFeatureStatus.mayRun(RenderFeature.PARTICLES);
    }

    /** Copies the distances. Call with no render pass open, after the solid world and before translucent terrain. */
    public void take(RenderTarget mainTarget) {
        if (!wanted()) {
            return;
        }
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (depthView == null) {
            return;
        }
        try {
            int width = depthView.getWidth(0);
            int height = depthView.getHeight(0);
            if (texture == null || texture.getWidth(0) != width || texture.getHeight(0) != height) {
                closeTexture();
                var device = RenderSystem.getDevice();
                texture = device.createTexture("LiteLights haze depth", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.R16_FLOAT, width, height, 1, 1);
                view = device.createTextureView(texture);
            }
            try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights haze depth", view, Optional.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                // A pass of LiteLights' own gets the neutral frame by default; the copy needs the camera's matrices.
                LiteLightsRenderer.get().bindWorldUniforms(pass);
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
                pass.setUniform("InDepth", depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            taken = true;
        } catch (RuntimeException error) {
            taken = false;
            RenderFeatureStatus.failed(RenderFeature.PARTICLES, error);
        }
    }

    /** Binds the copy on every pass vanilla binds its defaults on; only the haze shaders read it. */
    public void bind(RenderPass pass) {
        GpuTextureView bound = taken && view != null && !view.isClosed() ? view : placeholderView;
        if (bound != null) {
            pass.setUniform(SAMPLER, bound, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
    }

    /** Once the GPU device exists, before anything is drawn (from RenderSystem.initRenderer): the 1 x 1 placeholder. */
    public void createDeviceResources() {
        var device = RenderSystem.getDevice();
        placeholder = device.createTexture("LiteLights haze depth placeholder", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        placeholderView = device.createTextureView(placeholder);
        ByteBuffer zero = MemoryUtil.memCalloc(4);
        try {
            device.createCommandEncoder().writeToTexture(placeholder, zero, 0, 0, 0, 0, 1, 1);
        } finally {
            MemoryUtil.memFree(zero);
        }
    }

    private void closeTexture() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        taken = false;
    }

    @Override
    public void close() {
        closeTexture();
        if (placeholderView != null) {
            placeholderView.close();
            placeholderView = null;
        }
        if (placeholder != null) {
            placeholder.close();
            placeholder = null;
        }
    }
}
