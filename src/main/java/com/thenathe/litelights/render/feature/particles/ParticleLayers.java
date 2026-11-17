package com.thenathe.litelights.render.feature.particles;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import com.thenathe.litelights.render.post.PostPipelines;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/**
 * The layers LiteLights' particles draw in. Translucent is vanilla's own translucent particle
 * layer. Glow adds light instead of covering what is behind (fireflies, embers, wisps): vanilla's
 * particle shader with additive blending and no depth writes, plus its Improved Transparency set,
 * built like vanilla's lightning, which glows the same way.
 *
 * <p>Fire's decals (burn marks and ember veins) lie on block faces. Their layers are solid ones, so
 * vanilla's particle renderer draws them in the solid phase right after the solid terrain, never through
 * Improved Transparency: the ember veins have to reach the main target's alpha, where bloom's
 * emissive mask is.
 */
final class ParticleLayers {
    static final SingleQuadParticle.Layer TRANSLUCENT = SingleQuadParticle.Layer.TRANSLUCENT;

    private static final RenderPipeline GLOW_PIPELINE = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withLocation(LiteLightsClient.id("pipeline/glow_particle"))
        .withVertexShader("core/particle")
        .withFragmentShader("core/particle")
        .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withColorTargetState(new ColorTargetState(PostPipelines.ADD_KEEP_ALPHA))
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .build();

    private static final OitPipelineSet GLOW_OIT = OitPipelineSet.builder("litelights_glow_particle", RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withVertexShader("core/particle")
            .withFragmentShader("core/particle")
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withShaderDefine("OIT_ADDITIVE"))
        .build();

    static final SingleQuadParticle.Layer GLOW = new SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_PARTICLES, GLOW_PIPELINE, GLOW_OIT);

    /**
     * Soft puffs (mist, haze, dust clouds): LiteLights' own particle shader, which keeps faint
     * edges that vanilla's 10 % cut-off would square off. Blended over what is behind without
     * writing depth, so puffs never cut each other, and leaving the target's alpha alone.
     */
    private static final BlendFunction OVER_KEEP_ALPHA = new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
        BlendFactor.ZERO, BlendFactor.ONE);
    private static final Identifier SOFT_SHADER = LiteLightsClient.id("particle/soft");

    private static final RenderPipeline SOFT_PIPELINE = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withLocation(LiteLightsClient.id("pipeline/soft_particle"))
        .withVertexShader(SOFT_SHADER)
        .withFragmentShader(SOFT_SHADER)
        .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withColorTargetState(new ColorTargetState(OVER_KEEP_ALPHA))
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .build();

    private static final OitPipelineSet SOFT_OIT = OitPipelineSet.builder("litelights_soft_particle", RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withVertexShader(SOFT_SHADER)
            .withFragmentShader(SOFT_SHADER)
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
            .withPrimitiveTopology(PrimitiveTopology.QUADS))
        .build();

    static final SingleQuadParticle.Layer SOFT = new SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_PARTICLES, SOFT_PIPELINE, SOFT_OIT);

    /**
     * The haze kinds' classic billboards (Mist style: Classic billboards): the soft shader with
     * LITE_HAZE, which fades them out against the opaque scene's distance (HazeDepth) and breaks
     * them up with the drifting world-space noise. A layer of their own, so snow and sand grains
     * keep the plain soft shader.
     */
    private static final RenderPipeline HAZE_BILLBOARD_PIPELINE = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withBindGroupLayout(HazeDepth.LAYOUT)
        .withLocation(LiteLightsClient.id("pipeline/haze_billboard"))
        .withVertexShader(SOFT_SHADER)
        .withFragmentShader(SOFT_SHADER)
        .withShaderDefine("LITE_HAZE")
        .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withColorTargetState(new ColorTargetState(OVER_KEEP_ALPHA))
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .build();

    private static final OitPipelineSet HAZE_BILLBOARD_OIT = OitPipelineSet.builder("litelights_haze_billboard", RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(HazeDepth.LAYOUT)
            .withVertexShader(SOFT_SHADER)
            .withFragmentShader(SOFT_SHADER)
            .withShaderDefine("LITE_HAZE")
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
            .withPrimitiveTopology(PrimitiveTopology.QUADS))
        .build();

    static final SingleQuadParticle.Layer HAZE_BILLBOARD = new SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_PARTICLES,
        HAZE_BILLBOARD_PIPELINE, HAZE_BILLBOARD_OIT);

    /**
     * 3D haze puffs (Mist style: 3D puffs, the default): particle/puff traces each puff's ellipsoid
     * against the opaque scene's distance (HazeDepth). Blended like the soft layer; no culling, as the
     * vertex shader builds the proxy quad.
     */
    private static final Identifier PUFF_SHADER = LiteLightsClient.id("particle/puff");

    private static final RenderPipeline PUFF_PIPELINE = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withBindGroupLayout(HazeDepth.LAYOUT)
        .withLocation(LiteLightsClient.id("pipeline/haze_puff"))
        .withVertexShader(PUFF_SHADER)
        .withFragmentShader(PUFF_SHADER)
        .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withCull(false)
        .withColorTargetState(new ColorTargetState(OVER_KEEP_ALPHA))
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .build();

    private static final OitPipelineSet PUFF_OIT = OitPipelineSet.builder("litelights_haze_puff", RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(HazeDepth.LAYOUT)
            .withVertexShader(PUFF_SHADER)
            .withFragmentShader(PUFF_SHADER)
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false))
        .build();

    static final SingleQuadParticle.Layer PUFF = new SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_PARTICLES, PUFF_PIPELINE, PUFF_OIT);

    /**
     * Burn marks: the soft shader blended over the face (lit by the lightmap, faint edges kept),
     * without culling or depth writes. Lies a hair above the face, so the depth test keeps it there.
     */
    private static final RenderPipeline BURN_MARK_PIPELINE = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withLocation(LiteLightsClient.id("pipeline/burn_mark"))
        .withVertexShader(SOFT_SHADER)
        .withFragmentShader(SOFT_SHADER)
        .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withCull(false)
        .withColorTargetState(new ColorTargetState(OVER_KEEP_ALPHA))
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .build();

    static final SingleQuadParticle.Layer BURN_MARK = new SingleQuadParticle.Layer(false, TextureAtlas.LOCATION_PARTICLES, BURN_MARK_PIPELINE);

    /**
     * Ember veins add their colour, and the lower of the two alphas stays: the shader writes bloom's
     * emissive mark there (a low alpha), or 1 where it marks nothing, which keeps what was there.
     */
    private static final BlendFunction ADD_MARK_EMISSIVE = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.ADD,
        BlendFactor.ONE, BlendFactor.ONE, BlendOp.MIN);
    private static final Identifier EMBER_VEIN_SHADER = LiteLightsClient.id("particle/ember_vein");

    private static final RenderPipeline EMBER_VEIN_PIPELINE = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withLocation(LiteLightsClient.id("pipeline/ember_vein"))
        .withVertexShader(EMBER_VEIN_SHADER)
        .withFragmentShader(EMBER_VEIN_SHADER)
        .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withCull(false)
        .withColorTargetState(new ColorTargetState(ADD_MARK_EMISSIVE))
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .build();

    static final SingleQuadParticle.Layer EMBER_VEIN = new SingleQuadParticle.Layer(false, TextureAtlas.LOCATION_PARTICLES, EMBER_VEIN_PIPELINE);

    private ParticleLayers() {
    }

    /** These shaders are LiteLights' own: a compile failure greys out the particle rows, not shading. */
    static void claimPipelines() {
        PipelineOwners.claim(RenderFeature.PARTICLES, SOFT_PIPELINE, SOFT_OIT.depthBoundsPipeline(), SOFT_OIT.transmittancePipeline(),
            SOFT_OIT.accumulatePipeline(), BURN_MARK_PIPELINE, EMBER_VEIN_PIPELINE);
        PipelineOwners.claim(RenderFeature.PARTICLES, HAZE_BILLBOARD_PIPELINE, HAZE_BILLBOARD_OIT.depthBoundsPipeline(),
            HAZE_BILLBOARD_OIT.transmittancePipeline(), HAZE_BILLBOARD_OIT.accumulatePipeline(), PUFF_PIPELINE, PUFF_OIT.depthBoundsPipeline(),
            PUFF_OIT.transmittancePipeline(), PUFF_OIT.accumulatePipeline());
        HazeDepth.claimPipelines();
    }

    static SingleQuadParticle.Layer of(ParticleKind.Layer layer) {
        return switch (layer) {
            case GLOW -> GLOW;
            case SOFT -> SOFT;
            case TRANSLUCENT -> TRANSLUCENT;
        };
    }
}
