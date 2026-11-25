package com.thenathe.litelights.render.feature.weather.snow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Fake snow follows the block's shape: each step of a stair, a slab's top, a wall's or a fence's top. */
class SnowSurfacesTest {
    private static int area(SnowSurfaces surfaces, int height) {
        return surfaces.tops().stream().filter(top -> top.top() == height)
            .mapToInt(top -> (top.maxX() - top.minX()) * (top.maxZ() - top.minZ())).sum();
    }

    @Test
    void aStairCarriesSnowOnEachStepAtTheStepsHeight() {
        // A bottom stair facing north: the lower half, and the upper step on the north half.
        SnowSurfaces stair = SnowSurfaces.ofBoxes(new double[] {0, 0, 0, 1, 0.5, 1, 0, 0.5, 0, 1, 1, 0.5});
        assertEquals(128, area(stair, 16), "the upper step's top, half the footprint");
        assertEquals(128, area(stair, 8), "the lower step's top, at half a block");
        assertEquals(2, stair.tops().size());
        assertFalse(stair.fullTop());
        assertEquals(16, stair.heightAt(5, 2));
        assertEquals(8, stair.heightAt(5, 12));
    }

    @Test
    void aBottomSlabCarriesItOnTheSlabsTop() {
        SnowSurfaces slab = SnowSurfaces.ofBoxes(new double[] {0, 0, 0, 1, 0.5, 1});
        assertEquals(List.of(new SnowSurfaces.Top(0, 0, 16, 16, 8)), slab.tops());
    }

    @Test
    void aWallOrFenceCarriesItAlongItsTop() {
        // A wall post with one side running east, lower than the post.
        SnowSurfaces wall = SnowSurfaces.ofBoxes(new double[] {0.25, 0, 0.25, 0.75, 1, 0.75, 0.75, 0, 0.3125, 1, 0.875, 0.6875});
        assertEquals(64, area(wall, 16), "the post");
        assertEquals(4 * 6, area(wall, 14), "the side, at its own height");
        assertEquals(0, wall.heightAt(0, 0), "no snow where the shape has none");
        // A fence post with an arm: two sixteenths wide along its top.
        SnowSurfaces fence = SnowSurfaces.ofBoxes(new double[] {0.375, 0, 0.375, 0.625, 1, 0.625, 0.4375, 0, 0, 0.5625, 1, 0.375});
        assertEquals(16 + 2 * 6, area(fence, 16));
    }

    @Test
    void aFullTopNeedsNoFakeSnow() {
        assertTrue(SnowSurfaces.ofBoxes(new double[] {0, 0, 0, 1, 1, 1}).fullTop());
        assertTrue(SnowSurfaces.ofBoxes(new double[0]).isEmpty());
    }

    @Test
    void partsOutsideTheBlockAreLeftOut() {
        SnowSurfaces tall = SnowSurfaces.ofBoxes(new double[] {-0.5, 0, 0.25, 1.5, 1.5, 0.75});
        assertEquals(List.of(new SnowSurfaces.Top(0, 4, 16, 12, 24)), tall.tops());
    }
}
