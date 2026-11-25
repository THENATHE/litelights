package com.thenathe.litelights.render.post;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.thenathe.litelights.LiteLightsClient;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Builds full-screen post pipelines. Every one draws a single triangle that covers the target
 * ({@code litelights:post/fullscreen} vertex shader, {@code draw(3, 1, 0, 0)}) and reads its inputs
 * through samplers; small per-pass values travel as push constants (128 bytes at most).
 * LiteLights' own uniforms (LiteFrame, the shadow maps, the material texture) are added to every
 * pipeline automatically.
 */
public final class PostPipelines {
    /** Colour write masks: red, green and blue only (the main target's alpha is the emissive mask), or every channel. */
    public static final int WRITE_RGB = 0b0111;
    public static final int WRITE_RGBA = 0b1111;

    /** Writes the main target's colour as it is, every channel. */
    public static final ColorTargetState MAIN_TARGET = ColorTargetState.DEFAULT;
    /** Light screen-blended onto the main target: it brightens without ever passing white. */
    public static final BlendFunction SCREEN = new BlendFunction(BlendFactor.ONE_MINUS_DST_COLOR, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE);
    /** Screen-blends onto the main target's colour and leaves its alpha (the emissive mask) alone. */
    public static final ColorTargetState MAIN_TARGET_SCREEN = new ColorTargetState(Optional.of(SCREEN), GpuFormat.RGBA8_UNORM, WRITE_RGB);
    /**
     * Adds light scaled by its alpha (like vanilla's lightning) but leaves the target's alpha alone:
     * during the world stage the main target's alpha is the bloom mask (docs/ARCHITECTURE.md, "The
     * post chain"), and a glow drawn over a glowing block must not clear its mark.
     */
    public static final BlendFunction ADD_KEEP_ALPHA = new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE);
    /** A half-float working texture (bloom, light rays). */
    public static final ColorTargetState HALF_FLOAT = new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, WRITE_RGBA);
    public static final ColorTargetState HALF_FLOAT_ADD = new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), GpuFormat.RGBA16_FLOAT, WRITE_RGBA);

    private PostPipelines() {
    }

    /** A bind group of combined image samplers with these names. */
    public static BindGroupLayout samplers(String... names) {
        BindGroupLayout.Builder builder = BindGroupLayout.builder();
        for (String name : names) {
            builder.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        return builder.build();
    }

    /**
     * Starts a full-screen pipeline with the fragment shader {@code litelights:post/<fragment>}. Add
     * defines or change the vertex shader on the returned builder before {@code build()}.
     */
    public static RenderPipeline.Builder fullScreen(String name, String fragment, BindGroupLayout samplers, ColorTargetState target,
                                                    int pushConstantSize) {
        return fullScreen(name, LiteLightsClient.id("post/" + fragment), samplers, target, pushConstantSize);
    }

    /** The same, with a fragment shader from anywhere. */
    public static RenderPipeline.Builder fullScreen(String name, Identifier fragmentShader, BindGroupLayout samplers, ColorTargetState target,
                                                    int pushConstantSize) {
        return RenderPipeline.builder()
            .withLocation(LiteLightsClient.id("pipeline/post/" + name))
            .withVertexShader(LiteLightsClient.id("post/fullscreen"))
            .withFragmentShader(fragmentShader)
            .withBindGroupLayout(samplers)
            .withColorTargetState(target)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withPushConstantSize(pushConstantSize);
    }
}
