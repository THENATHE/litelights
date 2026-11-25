package com.thenathe.litelights.render.pipeline;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PipelineVariantsTest {
    private static final RenderPipeline PLAIN = RenderPipelines.ITEM_CUTOUT;
    private static final RenderPipeline GLINT = RenderPipelines.ITEM_CUTOUT_GLINT;

    @Test
    void vanillaItemPipelinesShareALocationButNotADefinition() {
        // The case this lookup exists for; if vanilla ever gives them their own locations, the
        // shared-location handling is simply never needed.
        assertEquals(PLAIN.getLocation(), GLINT.getLocation());
        assertNotEquals(PLAIN.getShaderDefines(), GLINT.getShaderDefines());
    }

    @Test
    void pipelinesSharingALocationAreAllKept() {
        List<RenderPipeline> known = PipelineVariants.withRegistered(null, PLAIN);
        known = PipelineVariants.withRegistered(known, GLINT);
        assertEquals(List.of(PLAIN, GLINT), known);
    }

    @Test
    void aRebuiltPipelineReplacesItsOlderCopy() {
        List<RenderPipeline> known = PipelineVariants.withRegistered(null, PLAIN);
        known = PipelineVariants.withRegistered(known, GLINT);
        known = PipelineVariants.withRegistered(known, PLAIN);
        assertEquals(List.of(GLINT, PLAIN), known);
    }

    @Test
    void eachCompiledPipelineFindsItsOwnSource() {
        List<RenderPipeline> known = List.of(PLAIN, GLINT);
        // Whichever registered last, the plain item draw must get the plain item's caster variant.
        assertSame(PLAIN, PipelineVariants.pickSource(known, candidate -> candidate == PLAIN));
        assertSame(GLINT, PipelineVariants.pickSource(known, candidate -> candidate == GLINT));
        assertSame(PLAIN, PipelineVariants.pickSource(List.of(GLINT, PLAIN), candidate -> candidate == PLAIN));
    }

    @Test
    void aLonePipelineNeedsNoCheckAndAnUnknownOneIsNull() {
        assertSame(PLAIN, PipelineVariants.pickSource(List.of(PLAIN), candidate -> false));
        assertNull(PipelineVariants.pickSource(List.of(PLAIN, GLINT), candidate -> false));
    }
}
