package com.thenathe.litelights.render.pipeline;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;

/**
 * The names LiteLights binds on every render pass, and the bind group layout that declares them.
 * The layout is added to every render pipeline as it is built (see RenderPipelineBuilderMixin), so
 * any shader, ours or a phase-2 feature's, can use these uniforms without changing pipeline
 * definitions. Pipelines whose shaders don't use them pay nothing: only uniforms a shader actually
 * reads get a binding.
 */
public final class LiteBindings {
    public static final String FRAME = "LiteFrame";
    public static final String SUN_SHADOW = "LiteSunShadow";
    public static final String SUN_SHADOW_PREVIOUS = "LiteSunShadowPrev";
    public static final String MOB_SHADOW = "LiteMobShadow";
    public static final String MATERIALS = "LiteMaterials";

    public static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
        .withUniform(FRAME, UniformType.UNIFORM_BUFFER)
        .withUniform(SUN_SHADOW, UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform(SUN_SHADOW_PREVIOUS, UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform(MOB_SHADOW, UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform(MATERIALS, UniformType.COMBINED_IMAGE_SAMPLER)
        .build();

    private LiteBindings() {
    }
}
