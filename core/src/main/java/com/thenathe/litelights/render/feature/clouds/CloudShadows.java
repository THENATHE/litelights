package com.thenathe.litelights.render.feature.clouds;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostPipelines;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.lwjgl.system.MemoryStack;

/**
 * The Dynamic clouds' shadows on the land (the Cloud shadows row): one full-screen draw that
 * darkens the main target where the clouds stand between the land and the sun
 * ({@code shaders/clouds/cloud_shadow.fsh}), read from the same column maps the clouds are drawn
 * from, blended between builds as they are, so a shadow lies under its cloud and changes with it.
 * It falls on near and far land alike, since both are in the target by then, and on entities
 * drawn with the solid world. It is only the shadow on the land: the clouds' shading of
 * themselves is the march's, whatever this row says. With the row off it isn't drawn at all.
 * Render thread only.
 */
final class CloudShadows {
    /** The land's colour times the shadow; alpha (bloom's mask) kept. */
    private static final BlendFunction MULTIPLY = new BlendFunction(BlendFactor.ZERO, BlendFactor.SRC_COLOR, BlendFactor.ZERO, BlendFactor.ONE);
    private static final int PUSH_BYTES = 64;
    static final RenderPipeline PIPELINE = PostPipelines.fullScreen("cloud_shadows", LiteLightsClient.id("clouds/cloud_shadow"),
            PostPipelines.samplers("SceneDepth", "NearMap", "FarMap"),
            new ColorTargetState(Optional.of(MULTIPLY), GpuFormat.RGBA8_UNORM, PostPipelines.WRITE_RGB), PUSH_BYTES)
        .build();
    /** The deepest a cloud's shadow darkens the land, against its colour in full sun. */
    static final float DEEPEST_SHADE = 0.42f;

    private CloudShadows() {
    }

    /** Draws the shadows into the main target. Call with no render pass open, before the clouds. */
    static void draw(RenderTarget mainTarget, CloudVolume.Frame frame) {
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        GpuTextureView colourView = mainTarget.getColorTextureView();
        if (depthView == null || colourView == null) {
            return;
        }
        float nearHalf = frame.nearMap().spanBlocks() * 0.5f;
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights cloud shadows", colourView, Optional.empty());
             MemoryStack stack = MemoryStack.stackPush()) {
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsRenderer.get().bindWorldUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            pass.setUniform("SceneDepth", depthView, nearest);
            pass.setUniform("NearMap", frame.near(), linear);
            pass.setUniform("FarMap", frame.far(), linear);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putFloat(0, (float) frame.nearCornerX()).putFloat(4, (float) frame.nearCornerZ())
                .putFloat(8, frame.nearMap().texelBlocks()).putFloat(12, nearHalf * 0.85f);
            push.putFloat(16, (float) frame.farCornerX()).putFloat(20, (float) frame.farCornerZ())
                .putFloat(24, frame.farMap().texelBlocks()).putFloat(28, 0.0f);
            push.putFloat(32, (float) (frame.nearCornerX() + nearHalf)).putFloat(36, (float) (frame.nearCornerZ() + nearHalf))
                .putFloat(40, 0.0f).putFloat(44, frame.reach());
            push.putFloat(48, frame.floorY()).putFloat(52, DEEPEST_SHADE).putFloat(56, CloudVolume.EXTINCTION).putFloat(60, 0.0f);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
    }
}
