package com.thenathe.litelights.render.feature.farterrain.land;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.api.LiteLightsApi;
import com.thenathe.litelights.api.LiteLightsMod;
import com.thenathe.litelights.api.RenderPasses;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4fc;
import com.thenathe.litelights.render.feature.farterrain.FarLandSnow;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws far terrain into the main colour and depth at the split before translucent terrain, depth
 * tested against the near land already there. Culling is done on the CPU (FarSelection), and each
 * quad page is drawn with one {@code multiDraw}, in the form the backend is fastest with: separate
 * lists on OpenGL, interleaved pairs on Vulkan (measure-108). Tiles near the near terrain go
 * through a second pipeline that reads the seam mask and discards what the near terrain covers;
 * the rest skip that test, so the GPU can reject their hidden fragments before shading them.
 * Plants' cards go through a third, which also discards the gaps in their sprites. Water is drawn
 * apart ({@link #drawOverScene}), after water's scene copy, blended over what lies under it, and
 * after it the lights' points, added on.
 */
final class FarLandPass implements AutoCloseable {
    static final int CONSTANT_BYTES = 96;
    /**
     * The most draws one call of the separate-list form takes. radeonsi (Mesa 26.3) reserves too
     * little command space for a long list of draws whose shader reads gl_VertexID and overflowed
     * its command stream, aborting the game, once far water and lights made more lists; calls of
     * this many stay well inside it and cost nothing measurable.
     */
    private static final int SEPARATE_DRAWS_PER_CALL = 256;
    /** Share of its distance each position is pushed back: under a block at four kilometres. */
    private static final float DEPTH_PUSH = 2.0e-4f;

    private static final BindGroupLayout BINDINGS = BindGroupLayout.builder()
        .withUniform("FarQuads", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
        .withUniform("FarNodes", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
        .withUniform("FarAppearances", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
        .withUniform("FarAtlas", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("FarLightmap", UniformType.COMBINED_IMAGE_SAMPLER)
        .build();
    private static final BindGroupLayout SEAM_BINDINGS = BindGroupLayout.builder()
        .withUniform("FarSeam", UniformType.COMBINED_IMAGE_SAMPLER)
        .build();
    /** The visual snow's far snow (FarLandSnow): two more samplers, 12 in the busiest pipeline, OpenGL's limit. */
    private static final BindGroupLayout SNOW_BINDINGS = BindGroupLayout.builder()
        .withUniform("FarSnow", UniformType.UNIFORM_BUFFER)
        .withUniform("FarSnowLines", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("FarSnowColours", UniformType.COMBINED_IMAGE_SAMPLER)
        .build();

    static final RenderPipeline OPEN = pipeline("far_land", false, false, false);
    static final RenderPipeline SEAM = pipeline("far_land_seam", true, false, false);
    static final RenderPipeline CARDS = pipeline("far_land_cards", true, true, false);
    /** The same three with the season's snow on them (LITE_FAR_SNOW), used only while far snow draws. */
    static final RenderPipeline OPEN_SNOW = pipeline("far_land_snow", false, false, true);
    static final RenderPipeline SEAM_SNOW = pipeline("far_land_seam_snow", true, false, true);
    static final RenderPipeline CARDS_SNOW = pipeline("far_land_cards_snow", true, true, true);
    static final RenderPipeline WATER = overScenePipeline("far_land_water", BlendFunction.TRANSLUCENT, ColorTargetState.WRITE_ALL, true, "LITE_FAR_WATER");
    /** Far water's Plain look (FarLandSettings.WaterLook): the near water's shading only where it hands over. */
    static final RenderPipeline WATER_PLAIN = overScenePipeline("far_land_water_plain", BlendFunction.TRANSLUCENT, ColorTargetState.WRITE_ALL, true,
        "LITE_FAR_WATER", "LITE_FAR_WATER_PLAIN");
    /** The lights' glow is added to the colour only: the alpha keeps the emissive mark under it. */
    static final RenderPipeline LIGHTS = overScenePipeline("far_land_lights", BlendFunction.ADDITIVE, ColorTargetState.WRITE_COLOR, false, "LITE_FAR_LIGHTS");

    private @Nullable GpuTexture seamTexture;
    private @Nullable GpuTextureView seamView;
    private @Nullable ByteBuffer seamUpload;
    private @Nullable FarLandSnow snow;

    private static RenderPipeline pipeline(String name, boolean seam, boolean cards, boolean snow) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(LiteLightsMod.id("pipeline/farterrain/" + name))
            .withVertexShader(LiteLightsMod.id("farterrain/far_land"))
            .withFragmentShader(LiteLightsMod.id("farterrain/far_land"))
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BINDINGS)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withPushConstantSize(CONSTANT_BYTES);
        if (seam) {
            builder.withBindGroupLayout(SEAM_BINDINGS).withShaderDefine("LITE_FAR_SEAM");
        }
        if (cards) {
            builder.withShaderDefine("LITE_FAR_CARDS");
        }
        if (snow) {
            builder.withBindGroupLayout(SNOW_BINDINGS).withShaderDefine("LITE_FAR_SNOW");
        }
        return builder.build();
    }

    /**
     * A pipeline drawn over the scene copy, through the seam's test: far water blended over the
     * scene as near water is, writing depth; the lights added on, not writing it.
     */
    private static RenderPipeline overScenePipeline(String name, BlendFunction blend, int writeMask, boolean writeDepth, String... defines) {
        // Globals carries vanilla's Texture filtering choice, which far water samples the atlas by.
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(LiteLightsMod.id("pipeline/farterrain/" + name))
            .withVertexShader(LiteLightsMod.id("farterrain/far_land"))
            .withFragmentShader(LiteLightsMod.id("farterrain/far_land"))
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BINDINGS)
            .withBindGroupLayout(SEAM_BINDINGS)
            .withShaderDefine("LITE_FAR_SEAM")
            .withColorTargetState(new ColorTargetState(Optional.of(blend), GpuFormat.RGBA8_UNORM, writeMask))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, writeDepth))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withPushConstantSize(CONSTANT_BYTES);
        for (String define : defines) {
            builder.withShaderDefine(define);
        }
        return builder.build();
    }

    /** Puts the seam mask (land in red, water in green) on the GPU when it changed. */
    void uploadSeam(SeamCoverage coverage) {
        if (seamTexture == null) {
            var device = RenderSystem.getDevice();
            seamTexture = device.createTexture("LiteLights far terrain seam", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RG8_UNORM, SeamCoverage.SIZE, SeamCoverage.SIZE, 1, 1);
            seamView = device.createTextureView(seamTexture);
            seamUpload = MemoryUtil.memAlloc(SeamCoverage.SIZE * SeamCoverage.SIZE * SeamCoverage.CHANNELS);
            coverage.clear();
        }
        if (coverage.takeChanged()) {
            seamUpload.clear();
            seamUpload.put(coverage.values()).flip();
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(seamTexture, seamUpload, 0, 0, 0, 0,
                SeamCoverage.SIZE, SeamCoverage.SIZE);
        }
    }

    /** The far snow the opaque lists are drawn with this frame, or null for none. */
    void useSnow(@Nullable FarLandSnow farSnow) {
        snow = farSnow != null && farSnow.active() ? farSnow : null;
    }

    /**
     * Draws the three opaque lists. Call with no render pass open. {@code viewProjection} is the
     * level's own matrix for camera-relative positions, jitter included.
     */
    void draw(RenderTarget mainTarget, FarGpu gpu, GpuBuffer appearances, FarDrawList open, FarDrawList seam, FarDrawList cards,
              Matrix4fc viewProjection, float clock, int cameraChunkX, int cameraChunkZ, int seamRadius) {
        RenderPipeline[] pipelines = snow != null ? new RenderPipeline[] {OPEN_SNOW, SEAM_SNOW, CARDS_SNOW}
            : new RenderPipeline[] {OPEN, SEAM, CARDS};
        drawLists(mainTarget, gpu, appearances, new FarDrawList[] {open, seam, cards}, pipelines,
            viewProjection, clock, cameraChunkX, cameraChunkZ, seamRadius);
    }

    /**
     * Draws the water, in its Full or Plain look, then the lights, after water's scene copy. Call
     * with no render pass open.
     */
    void drawOverScene(RenderTarget mainTarget, FarGpu gpu, GpuBuffer appearances, FarDrawList water, FarDrawList lights, boolean plainWater,
                       Matrix4fc viewProjection, float clock, int cameraChunkX, int cameraChunkZ, int seamRadius) {
        drawLists(mainTarget, gpu, appearances, new FarDrawList[] {water, lights}, new RenderPipeline[] {plainWater ? WATER_PLAIN : WATER, LIGHTS},
            viewProjection, clock, cameraChunkX, cameraChunkZ, seamRadius);
    }

    private void drawLists(RenderTarget mainTarget, FarGpu gpu, GpuBuffer appearances, FarDrawList[] lists, RenderPipeline[] pipelines,
                           Matrix4fc viewProjection, float clock, int cameraChunkX, int cameraChunkZ, int seamRadius) {
        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (colorView == null || depthView == null || seamView == null) {
            return;
        }
        AbstractTexture atlasTexture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        GpuTextureView atlas = atlasTexture.getTextureView();
        int lastMip = Math.max(0, atlasTexture.getTexture().getMipLevels() - 1);
        GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.lightmap();
        RenderPasses passes = LiteLightsApi.passes();
        DeviceFeatures features = RenderSystem.getDevice().getDeviceInfo().features();
        int interleavedLimit = Math.max(1, RenderSystem.getDevice().getDeviceInfo().limits().maxMultiDrawDirectInterleavedDrawCount());
        try (RenderPass pass = passes.open(() -> "LiteLights far terrain", colorView, depthView);
             MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer constants = stack.malloc(CONSTANT_BYTES);
            viewProjection.get(0, constants);
            constants.putInt(64, cameraChunkX).putInt(68, cameraChunkZ).putInt(72, seamRadius).putInt(76, 0);
            constants.putFloat(80, DEPTH_PUSH).putFloat(84, lastMip).putFloat(88, clock).putFloat(92, 0.0f);
            for (int which = 0; which < lists.length; which++) {
                FarDrawList list = lists[which];
                if (list.isEmpty()) {
                    continue;
                }
                RenderPipeline pipeline = pipelines[which];
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                RenderSystem.bindDefaultUniforms(pass);
                LiteLightsApi.frame().bindWorldUniforms(pass);
                pass.setUniform("FarNodes", gpu.nodes());
                pass.setUniform("FarAppearances", appearances);
                // The water code filters the atlas by the footprint, as near water's own sampler does.
                pass.setUniform("FarAtlas", atlas, pipeline == WATER || pipeline == WATER_PLAIN ? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true)
                    : passes.nearestSampler());
                pass.setUniform("FarLightmap", lightmap, passes.linearSampler());
                if (pipeline != OPEN && pipeline != OPEN_SNOW) {
                    pass.setUniform("FarSeam", seamView, passes.nearestSampler());
                }
                if (snow != null && (pipeline == OPEN_SNOW || pipeline == SEAM_SNOW || pipeline == CARDS_SNOW)) {
                    snow.bind(pass);
                }
                pass.pushConstants(constants);
                List<FarDrawList.PageDraws> pages = list.pages();
                List<FarGpu.Page> gpuPages = gpu.pages();
                for (int index = 0; index < pages.size() && index < gpuPages.size(); index++) {
                    FarDrawList.PageDraws draws = pages.get(index);
                    if (draws.draws == 0) {
                        continue;
                    }
                    pass.setUniform("FarQuads", gpuPages.get(index).buffer);
                    multiDraw(pass, draws, features, interleavedLimit);
                }
            }
        }
    }

    private static void multiDraw(RenderPass pass, FarDrawList.PageDraws draws, DeviceFeatures features, int interleavedLimit) {
        if (features.multiDrawDirectSeparate()) {
            for (int start = 0; start < draws.draws; start += SEPARATE_DRAWS_PER_CALL) {
                int count = Math.min(SEPARATE_DRAWS_PER_CALL, draws.draws - start);
                draws.firsts.position(start).limit(start + count);
                draws.counts.position(start).limit(start + count);
                pass.multiDraw(draws.firsts.slice(), draws.counts.slice(), count);
            }
            draws.firsts.clear();
            draws.counts.clear();
        } else if (features.multiDrawDirectInterleaved()) {
            for (int start = 0; start < draws.draws; start += interleavedLimit) {
                int count = Math.min(interleavedLimit, draws.draws - start);
                draws.interleaved.position(start * 2).limit((start + count) * 2);
                pass.multiDraw(draws.interleaved.slice(), 1, 0, count);
            }
            draws.interleaved.clear();
        } else {
            for (int i = 0; i < draws.draws; i++) {
                pass.draw(draws.counts.get(i), 1, draws.firsts.get(i), 0);
            }
        }
    }

    @Override
    public void close() {
        if (seamView != null) {
            seamView.close();
            seamView = null;
        }
        if (seamTexture != null) {
            seamTexture.close();
            seamTexture = null;
        }
        if (seamUpload != null) {
            MemoryUtil.memFree(seamUpload);
            seamUpload = null;
        }
    }
}
