package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarShapeTest {
    @Test
    void aBottomSlabCoversItsBottomOnly() {
        FarShape slab = FarShape.ofBoxes(new int[] {0, 0, 0, 16, 8, 16});
        assertEquals(FarShape.BOXES, slab.type());
        assertTrue(slab.coversSide(FarQuad.DOWN));
        for (int face = FarQuad.UP; face <= FarQuad.EAST; face++) {
            assertFalse(slab.coversSide(face));
        }
    }

    @Test
    void stairsCoverTheirBottomAndBack() {
        FarShape stairs = FarShape.ofBoxes(new int[] {0, 0, 0, 16, 8, 16, 0, 8, 8, 16, 16, 16});
        assertTrue(stairs.coversSide(FarQuad.DOWN));
        assertTrue(stairs.coversSide(FarQuad.SOUTH), "the back is whole");
        assertFalse(stairs.coversSide(FarQuad.NORTH));
        assertFalse(stairs.coversSide(FarQuad.UP));
    }

    @Test
    void aPathCountsAsCoveringItsSides() {
        FarShape path = FarShape.ofBoxes(new int[] {0, 0, 0, 16, 15, 16});
        assertTrue(path.coversSide(FarQuad.NORTH), "fifteen sixteenths is near enough: a neighbour's face against it would be a sliver");
        assertFalse(path.coversSide(FarQuad.UP));
    }

    @Test
    void theLargestBoxesAreKeptAndTinyShapesDropped() {
        int[] many = new int[8 * 6];
        for (int i = 0; i < 8; i++) {
            int size = i + 2;
            System.arraycopy(new int[] {0, 0, 0, size, size, size}, 0, many, i * 6, 6);
        }
        FarShape shape = FarShape.ofBoxes(many);
        assertEquals(FarShape.MAX_BOXES, shape.boxCount());
        assertEquals(9, shape.boxes()[3], "the largest first");
        assertEquals(FarShape.NONE, FarShape.ofBoxes(new int[] {5, 0, 6, 11, 2, 10}).type(), "a button is too small to see far away");
    }
}
