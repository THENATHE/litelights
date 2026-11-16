package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPass;
import com.thenathe.litelights.render.post.PostPipelines;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

/**
 * FXAA over the level, before the hand and HUD are drawn, so they are never touched. The shader
 * only smooths pixels on a depth edge (a silhouette or a crease between faces); edges inside
 * textures stay exactly as drawn, so block and item pixel art stays crisp. When light rays drew
 * this frame, this pass also adds them, in place of their own composite pass.
 */
final class FxaaPass implements PostPass {
    private static final RenderPipeline PIPELINE = PostPipelines.fullScreen("fxaa", "fxaa",
        PostPipelines.samplers("SceneSampler", "DepthSampler", "RaysSampler"), PostPipelines.MAIN_TARGET, 48).build();

    /**
     * A neighbour this much nearer or farther (relative) counts as a different surface. Small
     * enough to catch a block's outline against the block behind it at a distance, large enough
     * that a sloped face never trips it.
     */
    private static final float DEPTH_EDGE = 0.02f;

    private LightRaysPass rays;

    void setRays(LightRaysPass rays) {
        this.rays = rays;
    }

    /** FXAA will run this frame: chosen, or standing in for TAA while TAA is failing. */
    boolean willRun(RenderSettings settings) {
        RenderSettings.AntiAliasing choice = settings.general().antiAliasing();
        boolean chosen = choice == RenderSettings.AntiAliasing.FXAA
            || choice == RenderSettings.AntiAliasing.TAA && !RenderFeatureStatus.mayRun(RenderFeature.TAA);
        return chosen && RenderFeatureStatus.mayRun(RenderFeature.FXAA);
    }

    @Override
    public void draw(PostContext context) {
        if (!willRun(context.settings())) {
            return;
        }
        try {
            boolean addRays = rays != null && rays.drawnThisFrame();
            GpuTextureView scene = context.copyOfScene();
            try (RenderPass pass = context.openFullScreen(() -> "LiteLights FXAA", PIPELINE, context.sceneColor());
                 MemoryStack stack = MemoryStack.stackPush()) {
                pass.setUniform("SceneSampler", scene, PostContext.linearSampler());
                pass.setUniform("DepthSampler", context.sceneDepth(), PostContext.nearestSampler());
                pass.setUniform("RaysSampler", addRays ? rays.raysView() : scene, PostContext.nearestSampler());
                ByteBuffer push = stack.malloc(48);
                push.putFloat(0, 1.0f / context.width());
                push.putFloat(4, 1.0f / context.height());
                push.putFloat(8, addRays ? 1.0f : 0.0f);
                push.putFloat(12, DEPTH_EDGE);
                push.putFloat(16, addRays ? 1.0f / rays.raysWidth() : 0.0f);
                push.putFloat(20, addRays ? 1.0f / rays.raysHeight() : 0.0f);
                push.putFloat(24, addRays && rays.shadeGate() ? 1.0f : 0.0f);
                push.putFloat(28, 0.0f);
                if (addRays) {
                    rays.lightColour().get(32, push);
                } else {
                    push.putFloat(32, 0.0f).putFloat(36, 0.0f).putFloat(40, 0.0f).putFloat(44, 0.0f);
                }
                pass.pushConstants(push);
                pass.draw(3, 1, 0, 0);
            }
            RenderFeatureStatus.succeeded(RenderFeature.FXAA);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.FXAA, error);
        }
    }

    static RenderPipeline pipeline() {
        return PIPELINE;
    }
}
