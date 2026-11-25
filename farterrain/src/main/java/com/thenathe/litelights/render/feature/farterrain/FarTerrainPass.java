package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.api.LiteLightsApi;
import com.thenathe.litelights.api.LiteLightsMod;
import com.thenathe.litelights.api.RenderPasses;
import com.thenathe.litelights.api.ScreenTexture;
import java.nio.ByteBuffer;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;

/**
 * The far-terrain pass (shaders/farterrain/far_terrain.fsh): one full-screen draw over a copy of
 * the target that puts far shadows on LiteLights' own terrain past the shadow map. Only colour is
 * written; alpha (bloom's emissive mask) is handed back as it was read.
 */
final class FarTerrainPass implements AutoCloseable {
    private static final String[] SAMPLERS = {"SceneColor", "SceneDepth", "LightmapSampler", "FarShadowSampler"};
    private static final ColorTargetState COLOUR_AND_ALPHA = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL);

    static final RenderPipeline MAIN = pipeline();

    private final ScreenTexture copy = LiteLightsApi.passes().screenTexture("far terrain copy", GpuFormat.RGBA8_UNORM, 1);

    private static RenderPipeline pipeline() {
        RenderPasses passes = LiteLightsApi.passes();
        return passes.fullScreenPipeline("far_terrain", LiteLightsMod.id("farterrain/far_terrain"),
            passes.samplers(SAMPLERS), COLOUR_AND_ALPHA, FarValues.BYTES).build();
    }

    /**
     * Draws into {@code colorView}, whose texture is {@code colorTexture} (RGBA8). Call with no render
     * pass open. farShadow is this frame's far shadow texture.
     */
    void draw(GpuTexture colorTexture, GpuTextureView colorView, GpuTextureView depthView, FarValues values, GpuTextureView farShadow) {
        int width = colorTexture.getWidth(0);
        int height = colorTexture.getHeight(0);
        GpuTextureView copyView = copy.ensure(width, height);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(colorTexture, copy.texture(), 0, 0, 0, 0, 0, width, height);
        RenderPasses passes = LiteLightsApi.passes();
        try (RenderPass pass = passes.open(() -> "LiteLights far terrain", colorView, Optional.empty());
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(MAIN));
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsApi.frame().bindWorldUniforms(pass);
            pass.setUniform("SceneColor", copyView, passes.nearestSampler());
            pass.setUniform("SceneDepth", depthView, passes.nearestSampler());
            pass.setUniform("LightmapSampler", Minecraft.getInstance().gameRenderer.lightmap(), passes.linearSampler());
            pass.setUniform("FarShadowSampler", farShadow, passes.nearestSampler());
            ByteBuffer push = stack.malloc(FarValues.BYTES);
            values.writeForTerrain(push);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
    }

    @Override
    public void close() {
        copy.close();
    }
}
