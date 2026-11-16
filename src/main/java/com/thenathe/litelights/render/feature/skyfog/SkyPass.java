package com.thenathe.litelights.render.feature.skyfog;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;

/**
 * One full-screen triangle drawn over vanilla's sky, before the terrain: the Realistic sky, the
 * horizon haze, LiteLights' sun, moon, stars and shooting stars, and the End's aura. Everything is
 * worked out per pixel from the view direction (see {@code shaders/skyfog/sky.fsh}); there is no
 * geometry to build. The terrain drawn afterwards covers it, so only visible sky costs anything.
 */
final class SkyPass {
    static final RenderPipeline PIPELINE = RenderPipeline.builder()
        .withLocation(LiteLightsClient.id("pipeline/sky"))
        .withVertexShader(LiteLightsClient.id("skyfog/sky"))
        .withFragmentShader(LiteLightsClient.id("skyfog/sky"))
        .withBindGroupLayout(BindGroupLayouts.FOG)
        // Colour only: the main target's alpha is light-post's bloom mask and stays as it is.
        .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM,
            ColorTargetState.WRITE_COLOR))
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .build();

    void draw(RenderTarget target) {
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights sky", target.getColorTextureView(), Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            pass.draw(3, 1, 0, 0);
        }
    }
}
