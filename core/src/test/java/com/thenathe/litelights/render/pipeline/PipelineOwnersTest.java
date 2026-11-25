package com.thenathe.litelights.render.pipeline;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.api.RenderFeature;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PipelineOwnersTest {
    private static RenderPipeline withShader(String name, String shader) {
        Identifier id = Identifier.parse(shader);
        return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LiteLightsClient.id("test/" + name))
            .withVertexShader(id)
            .withFragmentShader(id)
            .build();
    }

    @Test
    void aPostShaderFailureBelongsToItsOwnFeatureAndHasNoFallback() {
        RenderPipeline bloom = withShader("bloom", "litelights:post/bloom_prefilter");
        PipelineOwners.claim(RenderFeature.BLOOM, bloom);
        assertEquals(RenderFeature.BLOOM, PipelineOwners.ownerOf(bloom));
        assertNull(PipelineVariants.fallbackFor(bloom));
    }

    @Test
    void anUnclaimedLiteLightsPipelineCountsAsPostProcessingNotShadows() {
        RenderPipeline unclaimed = withShader("unclaimed", "litelights:post/some_new_pass");
        assertEquals(RenderFeature.POST_PROCESSING, PipelineOwners.ownerOf(unclaimed));
    }

    @Test
    void replacementShadersBelongToTheShadingAndFallBackToTheOriginal() {
        RenderPipeline terrain = withShader("terrain", "litelights:core/terrain");
        RenderPipeline entity = withShader("entity", "litelights:core/entity");
        RenderPipeline sodium = withShader("sodium", "litelights:sodium/block_layer_opaque");
        assertEquals(RenderFeature.TERRAIN_SHADING, PipelineOwners.ownerOf(terrain));
        assertEquals(RenderFeature.ENTITY_SHADING, PipelineOwners.ownerOf(entity));
        assertEquals(RenderFeature.SODIUM_TERRAIN, PipelineOwners.ownerOf(sodium));
        assertNotNull(PipelineVariants.fallbackFor(terrain));
    }

    @Test
    void theCloudShaderBelongsToTheCloudsRow() {
        RenderPipeline clouds = withShader("clouds", "litelights:core/clouds");
        assertEquals(RenderFeature.CLOUDS, PipelineOwners.ownerOf(clouds));
        assertNotNull(PipelineVariants.fallbackFor(clouds));
    }

    @Test
    void otherModsPipelinesAreNotOurs() {
        assertNull(PipelineOwners.ownerOf(RenderPipelines.ITEM_CUTOUT));
        assertNull(PipelineVariants.fallbackFor(RenderPipelines.ITEM_CUTOUT));
    }
}
