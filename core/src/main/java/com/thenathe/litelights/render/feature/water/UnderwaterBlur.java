package com.thenathe.litelights.render.feature.water;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPipelines;

/**
 * Underwater depth blur: while the camera is under water, things farther away go soft. One
 * full-screen pass in the post chain's UNDERWATER slot (after the level, before the hand), reading
 * the chain's copy of the frame and the depth buffer; it never runs above water. It keeps the main
 * target's alpha, which carries bloom's emissive mask.
 */
final class UnderwaterBlur {
    static final RenderPipeline PIPELINE = PostPipelines.fullScreen("underwater_blur", LiteLightsClient.id("water/underwater_blur"),
        PostPipelines.samplers("BlurSource", "BlurDepth"), PostPipelines.MAIN_TARGET, 0).build();

    void draw(PostContext context) {
        GpuTextureView source = context.copyOfScene();
        try (RenderPass pass = context.openFullScreen(() -> "LiteLights underwater blur", PIPELINE, context.sceneColor())) {
            pass.setUniform("BlurSource", source, PostContext.linearSampler());
            pass.setUniform("BlurDepth", context.sceneDepth(), PostContext.nearestSampler());
            pass.draw(3, 1, 0, 0);
        }
    }
}
