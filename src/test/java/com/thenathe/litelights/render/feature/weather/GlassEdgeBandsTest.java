package com.thenathe.litelights.render.feature.weather;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bands frost, snow and sand draw along a window's edges (lite_glass_band_width and
 * lite_glass_edge_depth in include/glass/weather.glsl, mirrored in GlassShaderMirror): wider on
 * larger windows, fuller in the corners, deeper at the bottom, never filling the window.
 */
class GlassEdgeBandsTest {
    private static final double TEXEL = 1.0 / 16.0;

    /** Texels of a 16x face of a window of w x h blocks that a band reaching {@code reach} covers. */
    private static int coveredTexels(int width, int height, double band, double bottom, double reach) {
        int covered = 0;
        for (int v = 0; v < height * 16; v++) {
            for (int u = 0; u < width * 16; u++) {
                if (depthAt(width, height, u, v, band, bottom) < reach) {
                    covered++;
                }
            }
        }
        return covered;
    }

    private static double depthAt(int width, int height, int u, int v, double band, double bottom) {
        int blockU = u / 16;
        int blockV = v / 16;
        double[] runs = {blockU, width - 1 - blockU, blockV, height - 1 - blockV};
        double[] inBlock = {(u % 16 + 0.5) * TEXEL, (v % 16 + 0.5) * TEXEL};
        return GlassShaderMirror.edgeDepth(runs, inBlock, band, bottom);
    }

    @Test
    void bandsAreThinOnSmallWindowsAndWiderOnLargeOnes() {
        assertEquals(0.12, GlassShaderMirror.bandWidth(1), 1.0e-9, "about two texels at 16x on a single block");
        assertTrue(GlassShaderMirror.bandWidth(2) > GlassShaderMirror.bandWidth(1));
        assertTrue(GlassShaderMirror.bandWidth(5) > GlassShaderMirror.bandWidth(2));
        assertEquals(0.6, GlassShaderMirror.bandWidth(8), 1.0e-9, "about ten texels at most");
        assertEquals(0.6, GlassShaderMirror.bandWidth(40), 1.0e-9);
    }

    @Test
    void cornersHoldMoreThanStraightEdges() {
        double band = GlassShaderMirror.bandWidth(4);
        // Along the bottom edge in the middle of a 4 x 4 window, and along the diagonal from its corner.
        int straight = 0;
        while (depthAt(4, 4, 32, straight, band, 1.0) < band) {
            straight++;
        }
        int diagonal = 0;
        while (depthAt(4, 4, diagonal, diagonal, band, 1.0) < band) {
            diagonal++;
        }
        assertEquals(Math.round(band * 16), straight, 1, "the edge band's own width");
        assertTrue(diagonal >= straight * 1.5, "the corner fills in: " + diagonal + " texels along the diagonal, " + straight + " straight");
    }

    @Test
    void theBandFollowsTheWholeWindowNotEachBlock() {
        double band = GlassShaderMirror.bandWidth(3);
        // The middle block of a 3 x 3 window has no band at all, though each block has edges.
        for (int v = 16; v < 32; v++) {
            for (int u = 16; u < 32; u++) {
                assertTrue(depthAt(3, 3, u, v, band, 1.0) >= band * GlassShaderMirror.MOST_RAGGED);
            }
        }
    }

    @Test
    void aDeeperBottomMakesTheBandThickerThereOnly() {
        double band = GlassShaderMirror.bandWidth(4);
        int bottom = 0;
        while (depthAt(4, 4, 32, bottom, band, 2.0) < band) {
            bottom++;
        }
        int top = 0;
        while (depthAt(4, 4, 32, 63 - top, band, 2.0) < band) {
            top++;
        }
        assertEquals(2.0 * top, bottom, 1.5, "twice as deep at the bottom");
        assertTrue(coveredTexels(4, 4, band, 2.0, band) > coveredTexels(4, 4, band, 1.0, band));
    }

    @Test
    void noWindowIsEverFilled() {
        for (int size = 1; size <= 10; size++) {
            double band = GlassShaderMirror.bandWidth(size);
            double reach = band * GlassShaderMirror.MOST_RAGGED;
            // Snow's deepest: piled on a ledge. Even then most of a window's glass stays clear.
            double bottom = GlassShaderMirror.snowBottom(band, size, true);
            int covered = coveredTexels(size, size, band, bottom, band);
            int all = size * size * 256;
            assertTrue(covered < all * 0.6, size + " x " + size + ": " + covered + " of " + all);
            // The window's middle stays clear, even where the ragged edge reaches furthest.
            int middle = size * 8;
            assertTrue(depthAt(size, size, middle, middle, band, bottom) >= reach, size + " x " + size + " middle");
        }
    }

    @Test
    void frostAndSandAreThinnerThanSnowAndTheirLooseEdgesLeaveTheMiddleClear() {
        assertEquals(0.78, GlassShaderMirror.FROST_BAND, 1.0e-9);
        assertEquals(0.68, GlassShaderMirror.SAND_BAND, 1.0e-9, "0.8 times glass17's 0.85");
        for (int size = 1; size <= 10; size++) {
            double snow = GlassShaderMirror.bandWidth(size);
            double frost = snow * GlassShaderMirror.FROST_BAND;
            double sand = snow * GlassShaderMirror.SAND_BAND;
            double frostReach = frost * GlassShaderMirror.MOST_RAGGED + GlassShaderMirror.frostFeatherReach(frost, 16.0);
            double sandReach = sand * GlassShaderMirror.MOST_RAGGED + GlassShaderMirror.looseSandReach(16.0);
            assertTrue(frostReach <= snow * GlassShaderMirror.MOST_RAGGED + 1.0e-9,
                size + " x " + size + ": frost's feathers reach no further than snow's ragged edge");
            int middle = size * 8;
            assertTrue(depthAt(size, size, middle, middle, frost, 1.0) >= frostReach, size + " x " + size + " frost middle");
            assertTrue(depthAt(size, size, middle, middle, sand, 1.4) >= sandReach, size + " x " + size + " sand middle");
        }
        // The corners stay as full in proportion: the diagonal still reaches half as far again as the edge.
        double frost = GlassShaderMirror.bandWidth(4) * GlassShaderMirror.FROST_BAND;
        int straight = 0;
        while (depthAt(4, 4, 32, straight, frost, 1.0) < frost) {
            straight++;
        }
        int diagonal = 0;
        while (depthAt(4, 4, diagonal, diagonal, frost, 1.0) < frost) {
            diagonal++;
        }
        assertTrue(diagonal >= straight * 1.5, diagonal + " texels along the diagonal, " + straight + " straight");
    }

    @Test
    void growingFrostStartsInTheCorners() {
        double full = GlassShaderMirror.bandWidth(5) * GlassShaderMirror.FROST_BAND;
        // A few percent grown: under half a texel.
        double early = full * 0.07;
        assertTrue(depthAt(5, 4, 0, 0, early, 1.0) < early, "the corner texel shows first");
        assertTrue(depthAt(5, 4, 40, 0, early, 1.0) >= early, "the middle of an edge doesn't yet");
    }
}
