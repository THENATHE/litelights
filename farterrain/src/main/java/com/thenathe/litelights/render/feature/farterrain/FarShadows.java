package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.api.FarTerrainSettings.FarShadowMode;
import com.thenathe.litelights.api.LiteLightsApi;
import com.thenathe.litelights.api.LiteLightsMod;
import com.thenathe.litelights.api.RenderPasses;
import com.thenathe.litelights.api.ScreenTexture;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Far shadows at half resolution (shaders/farterrain/far_shadow.fsh): screen space, or Advanced,
 * which adds the heightfield of the loaded land ({@link FarHeightfield}). The far-terrain pass reads
 * the result with a depth-aware upsample. Nothing here exists until a mode that needs it is chosen,
 * and it is freed again when the mode changes away.
 */
final class FarShadows implements AutoCloseable {
    static final RenderPipeline SCREEN = pipeline("far_shadow_screen", false);
    static final RenderPipeline ADVANCED = pipeline("far_shadow_advanced", true);

    /** How far Advanced marches toward the light, in blocks: about a mountain's shadow at a low sun. */
    private static final float MARCH_DISTANCE = 384.0f;

    private final FarHeightfield heightfield = new FarHeightfield();
    private @Nullable ScreenTexture target;

    private static RenderPipeline pipeline(String name, boolean heightfield) {
        RenderPasses passes = LiteLightsApi.passes();
        RenderPipeline.Builder builder = passes.fullScreenPipeline(name, LiteLightsMod.id("farterrain/far_shadow"),
            heightfield ? passes.samplers("SceneDepth", "HeightField") : passes.samplers("SceneDepth"),
            passes.halfFloatTarget(), FarValues.BYTES);
        if (heightfield) {
            builder.withShaderDefine("LITE_FAR_SHADOW_HEIGHTFIELD");
        }
        return builder.build();
    }

    FarHeightfield heightfield() {
        return heightfield;
    }

    /** Keeps only what the chosen mode needs: the heightfield exists in Advanced alone. */
    void applyMode(FarShadowMode mode) {
        if (mode != FarShadowMode.ADVANCED) {
            heightfield.close();
        }
        if (mode == FarShadowMode.OFF) {
            closeTargets();
        }
    }

    /**
     * Works out far shadows for the pixels of depthView. Fills the shadow texel size into values and
     * returns the texture, at half the depth texture's size.
     */
    GpuTextureView draw(GpuTextureView depthView, FarValues values, FarShadowMode mode) {
        int width = depthView.getWidth(0);
        int height = depthView.getHeight(0);
        ScreenTexture target = target();
        GpuTextureView targetView = target.ensure(width, height);
        values.shadowTexelX = 1.0f / target.width();
        values.shadowTexelY = 1.0f / target.height();
        boolean advanced = mode == FarShadowMode.ADVANCED && heightfield.view() != null;
        values.heightfieldMinY = heightfield.minY();
        values.heightfieldReach = heightfield.reach();
        values.marchDistance = MARCH_DISTANCE;
        RenderPipeline pipeline = advanced ? ADVANCED : SCREEN;
        RenderPasses passes = LiteLightsApi.passes();
        try (RenderPass pass = passes.open(() -> "LiteLights far shadows", targetView, Optional.empty());
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsApi.frame().bindWorldUniforms(pass);
            pass.setUniform("SceneDepth", depthView, passes.nearestSampler());
            if (advanced) {
                pass.setUniform("HeightField", heightfield.view(), passes.nearestSampler());
            }
            ByteBuffer push = stack.malloc(FarValues.BYTES);
            values.writeForShadows(push);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
        return targetView;
    }

    private ScreenTexture target() {
        if (target == null) {
            target = LiteLightsApi.passes().screenTexture("far shadows", GpuFormat.RGBA16_FLOAT, 2);
        }
        return target;
    }

    private void closeTargets() {
        if (target != null) {
            target.close();
            target = null;
        }
    }

    String describe() {
        return "shadowTargets=" + (target != null) + ", heightfield=" + (heightfield.view() != null);
    }

    @Override
    public void close() {
        heightfield.close();
        closeTargets();
    }
}
