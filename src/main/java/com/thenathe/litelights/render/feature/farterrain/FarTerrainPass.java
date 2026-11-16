package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPipelines;
import com.thenathe.litelights.render.post.PostTexture;
import java.nio.ByteBuffer;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * The far-terrain pass (shaders/farterrain/far_terrain.fsh): one full-screen draw over a copy of
 * the target. It shades another mod's far terrain with LiteLights' light, water and air, and puts
 * far shadows on LiteLights' own terrain past the shadow map. Only colour is written.
 */
final class FarTerrainPass implements AutoCloseable {
    private static final ColorTargetState COLOUR_ONLY = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, PostPipelines.WRITE_RGB);

    static final RenderPipeline MAIN = pipeline("far_terrain", false, false);
    static final RenderPipeline DISTANT_HORIZONS = pipeline("far_terrain_dh", true, false);
    /** The same with the Experimental far water extras compiled in (far caustics, depth colour, edge foam). */
    static final RenderPipeline MAIN_WATER_DETAILS = pipeline("far_terrain_water_details", false, true);
    static final RenderPipeline DISTANT_HORIZONS_WATER_DETAILS = pipeline("far_terrain_dh_water_details", true, true);

    private final PostTexture mainCopy = new PostTexture("far terrain copy", GpuFormat.RGBA8_UNORM, 1);
    private final PostTexture distantHorizonsCopy = new PostTexture("far terrain copy (Distant Horizons)", GpuFormat.RGBA8_UNORM, 1);

    private static RenderPipeline pipeline(String name, boolean distantHorizons, boolean waterDetails) {
        RenderPipeline.Builder builder = PostPipelines.fullScreen(name, LiteLightsClient.id("farterrain/far_terrain"),
            PostPipelines.samplers("SceneColor", "SceneDepth", "LightmapSampler", "FarShadowSampler", "FarWaterA", "FarWaterB"),
            COLOUR_ONLY, FarValues.BYTES);
        if (distantHorizons) {
            builder.withShaderDefine("LITE_FAR_SOURCE_DH");
        }
        if (waterDetails) {
            builder.withShaderDefine("LITE_FAR_WATER_DETAILS");
        }
        return builder.build();
    }

    /**
     * Draws into {@code colorView}, whose texture is {@code colorTexture} (RGBA8). Call with no render
     * pass open. farShadow is this frame's far shadow texture, or null when far shadows are off.
     * waterA and waterB are the far water capture (FarWaterCapture), or null without one.
     */
    void draw(GpuTexture colorTexture, GpuTextureView colorView, GpuTextureView depthView, FarValues values,
              @Nullable GpuTextureView farShadow, @Nullable GpuTextureView waterA, @Nullable GpuTextureView waterB, boolean distantHorizons) {
        int width = colorTexture.getWidth(0);
        int height = colorTexture.getHeight(0);
        PostTexture copy = distantHorizons ? distantHorizonsCopy : mainCopy;
        GpuTextureView copyView = copy.ensure(width, height);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(colorTexture, copy.texture(), 0, 0, 0, 0, 0, width, height);
        RenderPipeline pipeline = values.anyWaterDetails()
            ? (distantHorizons ? DISTANT_HORIZONS_WATER_DETAILS : MAIN_WATER_DETAILS)
            : (distantHorizons ? DISTANT_HORIZONS : MAIN);
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights far terrain", colorView, Optional.empty());
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsRenderer.get().bindWorldUniforms(pass);
            pass.setUniform("SceneColor", copyView, PostContext.nearestSampler());
            pass.setUniform("SceneDepth", depthView, PostContext.nearestSampler());
            pass.setUniform("LightmapSampler", Minecraft.getInstance().gameRenderer.lightmap(), PostContext.linearSampler());
            // Never read while far shadows are off (FarSwitches.z is 0), but something must be bound.
            pass.setUniform("FarShadowSampler", farShadow != null ? farShadow : copyView, PostContext.nearestSampler());
            // Only read while values.waterCaptured says there is a capture.
            pass.setUniform("FarWaterA", waterA != null ? waterA : copyView, PostContext.nearestSampler());
            pass.setUniform("FarWaterB", waterB != null ? waterB : copyView, PostContext.nearestSampler());
            ByteBuffer push = stack.malloc(FarValues.BYTES);
            values.writeForTerrain(push);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
    }

    @Override
    public void close() {
        mainCopy.close();
        distantHorizonsCopy.close();
    }
}
