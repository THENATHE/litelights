package com.thenathe.litelights.render.shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreedyFaceMergerTest {
    private static List<Integer> merge(boolean[] mask) {
        List<Integer> rectangles = new ArrayList<>();
        GreedyFaceMerger.merge(mask, rectangles::add);
        return rectangles;
    }

    @Test
    void fullSliceBecomesOneQuad() {
        boolean[] mask = new boolean[256];
        java.util.Arrays.fill(mask, true);
        List<Integer> rectangles = merge(mask);
        assertEquals(1, rectangles.size());
        int rectangle = rectangles.getFirst();
        assertEquals(16, GreedyFaceMerger.width(rectangle));
        assertEquals(16, GreedyFaceMerger.height(rectangle));
    }

    @Test
    void emptySliceHasNoQuads() {
        assertTrue(merge(new boolean[256]).isEmpty());
    }

    @Test
    void rectanglesCoverExactlyTheMaskedCells() {
        Random random = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            boolean[] mask = new boolean[256];
            for (int i = 0; i < mask.length; i++) {
                mask[i] = random.nextInt(3) != 0;
            }
            boolean[] expected = mask.clone();
            int[] covered = new int[256];
            for (int rectangle : merge(mask)) {
                for (int v = GreedyFaceMerger.v(rectangle); v < GreedyFaceMerger.v(rectangle) + GreedyFaceMerger.height(rectangle); v++) {
                    for (int u = GreedyFaceMerger.u(rectangle); u < GreedyFaceMerger.u(rectangle) + GreedyFaceMerger.width(rectangle); u++) {
                        covered[u + v * 16]++;
                    }
                }
            }
            for (int i = 0; i < 256; i++) {
                assertEquals(expected[i] ? 1 : 0, covered[i], "cell " + i + " in trial " + trial);
                assertFalse(mask[i], "the mask is consumed");
            }
        }
    }

    @Test
    void lShapeNeedsTwoQuads() {
        boolean[] mask = new boolean[256];
        for (int u = 0; u < 4; u++) {
            mask[u] = true;
        }
        for (int v = 0; v < 4; v++) {
            mask[v * 16] = true;
        }
        assertEquals(2, merge(mask).size());
    }
}
