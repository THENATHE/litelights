package com.thenathe.litelights.render.feature.vegetation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MirroredQuadsTest {
    @Test
    void aTopEdgeRunningRightToLeftIsMirrored() {
        assertTrue(MirroredQuads.isMirrored(0.50f, 0.25f));
        assertFalse(MirroredQuads.isMirrored(0.25f, 0.50f));
    }

    @Test
    void onlyOpaqueColoursTakeTheMark() {
        assertEquals(0xFE80C040, MirroredQuads.marked(0xFF80C040));
        assertEquals(0x7F80C040, MirroredQuads.marked(0x7F80C040));
    }

    @Test
    void theMarkReadsBelowTheShadersThreshold() {
        // terrain.vsh swaps the side when the vertex alpha is below 0.998.
        assertTrue(MirroredQuads.MARK_ALPHA / 255.0f < 0.998f);
        assertTrue(255 / 255.0f >= 0.998f);
    }
}
