package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPipelines;
import com.thenathe.litelights.render.post.PostTexture;
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
    static final RenderPipeline SCREEN = pipeline("far_shadow_screen", false, false);
    static final RenderPipeline SCREEN_DH = pipeline("far_shadow_screen_dh", true, false);
    static final RenderPipeline ADVANCED = pipeline("far_shadow_advanced", false, true);
    static final RenderPipeline ADVANCED_DH = pipeline("far_shadow_advanced_dh", true, true);

    /** How far Advanced marches toward the light, in blocks: about a mountain's shadow at a low sun. */
    private static final float MARCH_DISTANCE = 384.0f;

    private final FarHeightfield heightfield = new FarHeightfield();
    private @Nullable PostTexture mainTarget;
    private @Nullable PostTexture distantHorizonsTarget;

    private static RenderPipeline pipeline(String name, boolean distantHorizons, boolean heightfield) {
        RenderPipeline.Builder builder = PostPipelines.fullScreen(name, LiteLightsClient.id("farterrain/far_shadow"),
            heightfield ? PostPipelines.samplers("SceneDepth", "HeightField") : PostPipelines.samplers("SceneDepth"),
            PostPipelines.HALF_FLOAT, FarValues.BYTES);
        if (distantHorizons) {
            builder.withShaderDefine("LITE_FAR_SOURCE_DH");
        }
        if (heightfield) {
            builder.withShaderDefine("LITE_FAR_SHADOW_HEIGHTFIELD");
        }
        return builder.build();
    }

    FarHeightfield heightfield() {
        return heightfield;
    }

    /** Keeps only what the chosen mode needs: the heightfield exists in Advanced alone. */
    void applyMode(RenderSettings.FarShadows mode) {
        if (mode != RenderSettings.FarShadows.ADVANCED) {
            heightfield.close();
        }
        if (mode == RenderSettings.FarShadows.OFF) {
            closeTargets();
        }
    }

    /**
     * Works out far shadows for the pixels of depthView. Fills the shadow texel size into values and
     * returns the texture, at half the depth texture's size.
     */
    GpuTextureView draw(GpuTextureView depthView, FarValues values, RenderSettings.FarShadows mode, boolean distantHorizons) {
        int width = depthView.getWidth(0);
        int height = depthView.getHeight(0);
        PostTexture target = distantHorizons ? distantHorizonsTarget() : mainTarget();
        GpuTextureView targetView = target.ensure(width, height);
        values.shadowTexelX = 1.0f / target.width();
        values.shadowTexelY = 1.0f / target.height();
        boolean advanced = mode == RenderSettings.FarShadows.ADVANCED && heightfield.view() != null;
        values.heightfieldMinY = heightfield.minY();
        values.heightfieldReach = heightfield.reach();
        values.marchDistance = MARCH_DISTANCE;
        RenderPipeline pipeline = advanced ? (distantHorizons ? ADVANCED_DH : ADVANCED) : (distantHorizons ? SCREEN_DH : SCREEN);
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights far shadows", targetView, Optional.empty());
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsRenderer.get().bindWorldUniforms(pass);
            pass.setUniform("SceneDepth", depthView, PostContext.nearestSampler());
            if (advanced) {
                pass.setUniform("HeightField", heightfield.view(), PostContext.nearestSampler());
            }
            ByteBuffer push = stack.malloc(FarValues.BYTES);
            values.writeForShadows(push);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
        return targetView;
    }

    private PostTexture mainTarget() {
        if (mainTarget == null) {
            mainTarget = new PostTexture("far shadows", GpuFormat.RGBA16_FLOAT, 2);
        }
        return mainTarget;
    }

    private PostTexture distantHorizonsTarget() {
        if (distantHorizonsTarget == null) {
            distantHorizonsTarget = new PostTexture("far shadows (Distant Horizons)", GpuFormat.RGBA16_FLOAT, 2);
        }
        return distantHorizonsTarget;
    }

    private void closeTargets() {
        if (mainTarget != null) {
            mainTarget.close();
            mainTarget = null;
        }
        if (distantHorizonsTarget != null) {
            distantHorizonsTarget.close();
            distantHorizonsTarget = null;
        }
    }

    String describe() {
        return "shadowTargets=" + (mainTarget != null || distantHorizonsTarget != null) + ", heightfield=" + (heightfield.view() != null);
    }

    @Override
    public void close() {
        heightfield.close();
        closeTargets();
    }
}
