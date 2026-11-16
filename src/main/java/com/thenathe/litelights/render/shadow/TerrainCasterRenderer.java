package com.thenathe.litelights.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryStack;

/** Draws section caster meshes into the sun shadow map. */
public final class TerrainCasterRenderer {
    public static final VertexFormat CASTER_FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGBA16_UINT)
        .addAttribute("UV0", GpuFormat.RG16_UNORM)
        .build();
    private static final int PUSH_CONSTANT_SIZE = 64;

    /**
     * Depth only, both faces (a one-block wall casts from either side), with a little slope-scaled
     * depth bias on top of the normal offset the receiving shaders apply.
     */
    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
        .withLocation(LiteLightsClient.id("pipeline/terrain_shadow_casters"))
        .withVertexShader(LiteLightsClient.id("shadow/terrain_caster"))
        .withFragmentShader(LiteLightsClient.id("shadow/terrain_caster"))
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexBinding(0, CASTER_FORMAT)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 1.5f, 1.0f))
        .withCull(false)
        .withPushConstantSize(PUSH_CONSTANT_SIZE)
        .build();

    static {
        PipelineOwners.claim(RenderFeature.SHADOWS, PIPELINE);
    }

    private final Matrix4f sectionMatrix = new Matrix4f();

    /**
     * Draws one frame's share of a redraw into the map's back texture, clearing it first on the
     * redraw's first frame.
     */
    public void draw(SunShadowMap map, LongArrayList sections, ShadowCasterStore store, GpuTextureView blockAtlas, boolean clear) {
        RedrawTimings.begin();
        drawSections(map, sections, store, blockAtlas, clear);
        RedrawTimings.end(sections.size());
    }

    private void drawSections(SunShadowMap map, LongArrayList sections, ShadowCasterStore store, GpuTextureView blockAtlas, boolean clear) {
        CompiledRenderPipeline pipeline = RenderSystem.getCompiledPipeline(PIPELINE);
        int largestQuadCount = 0;
        for (int i = 0; i < sections.size(); i++) {
            ShadowCasterStore.SectionCasters casters = store.get(sections.getLong(i));
            if (casters != null) {
                largestQuadCount = Math.max(largestQuadCount, casters.quadCount());
            }
        }
        RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = quadIndices.getBuffer(Math.max(6, largestQuadCount * 6));

        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "LiteLights sun shadow")
            .withDepthAttachment(map.drawTarget(), clear ? OptionalDouble.of(1.0) : OptionalDouble.empty())
            .build();
        LightSpace space = map.drawingSpace();
        Vector3d anchor = space.anchor();
        try (RenderPass pass = GuardedRenderPass.open(descriptor);
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setPipeline(pipeline);
            pass.setUniform("Sampler0", blockAtlas, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setIndexBuffer(indexBuffer, quadIndices.type());
            ByteBuffer push = stack.malloc(PUSH_CONSTANT_SIZE);
            GpuBuffer boundPage = null;
            for (int i = 0; i < sections.size(); i++) {
                long key = sections.getLong(i);
                ShadowCasterStore.SectionCasters casters = store.get(key);
                if (casters == null) {
                    continue;
                }
                float originX = (float) (SectionPos.sectionToBlockCoord(SectionPos.x(key)) - anchor.x);
                float originY = (float) (SectionPos.sectionToBlockCoord(SectionPos.y(key)) - anchor.y);
                float originZ = (float) (SectionPos.sectionToBlockCoord(SectionPos.z(key)) - anchor.z);
                float offset = CasterMesh.POSITION_OFFSET;
                float scale = 1.0f / CasterMesh.POSITION_SCALE;
                sectionMatrix.set(space.viewProjection())
                    .translate(originX - offset, originY - offset, originZ - offset)
                    .scale(scale);
                sectionMatrix.get(0, push);
                pass.pushConstants(push);
                // Sections share a few large buffers; bind one only when it changes.
                if (casters.buffer() != boundPage) {
                    boundPage = casters.buffer();
                    pass.setVertexBuffer(0, boundPage.slice());
                }
                pass.drawIndexed(casters.quadCount() * 6, 1, 0, casters.firstVertex(), 0);
            }
        }
    }
}
