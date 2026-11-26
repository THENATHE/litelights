package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SeamCoverageTest {
    @Test
    void coveredColumnsFadeOutUncoveredShowAtOnce() {
        SeamCoverage seam = new SeamCoverage();
        seam.update(3, -4, true, 1000);
        assertEquals(0, seam.value(3, -4), "just covered: far terrain still shows");
        seam.update(3, -4, true, 1000 + SeamCoverage.FADE_MILLIS / 2);
        assertEquals(128, seam.value(3, -4), 1);
        seam.update(3, -4, true, 1000 + SeamCoverage.FADE_MILLIS);
        assertTrue(seam.fullyCovered(3, -4));
        seam.update(3, -4, false, 1000 + SeamCoverage.FADE_MILLIS + 1);
        assertEquals(0, seam.value(3, -4), "uncovered: far terrain is back at once, no hole");
    }

    @Test
    void wrappedSlotsDontLeakIntoAnotherChunk() {
        SeamCoverage seam = new SeamCoverage();
        seam.update(5, 5, true, 0);
        seam.update(5, 5, true, 10_000);
        assertTrue(seam.fullyCovered(5, 5));
        assertEquals(0, seam.value(5 + SeamCoverage.SIZE, 5), "the chunk a wrap away isn't covered");
        seam.update(5 + SeamCoverage.SIZE, 5, false, 10_001);
        assertFalse(seam.fullyCovered(5, 5), "the slot now belongs to the other chunk");
    }

    @Test
    void changesAreReportedForUpload() {
        SeamCoverage seam = new SeamCoverage();
        seam.update(0, 0, false, 0);
        seam.takeChanged();
        seam.update(0, 0, false, 5);
        assertFalse(seam.takeChanged(), "nothing moved");
        seam.update(0, 0, true, 5);
        seam.update(0, 0, true, 300);
        assertTrue(seam.takeChanged());
    }

    /** A column of sections: which the near renderer draws, and which hold only water and air. */
    private static SeamCoverage.Column column(Set<Integer> drawn, Set<Integer> onlyWater) {
        return new SeamCoverage.Column() {
            @Override
            public boolean drawn(int sectionY) {
                return drawn.contains(sectionY);
            }

            @Override
            public boolean onlyWater(int sectionY) {
                return onlyWater.contains(sectionY);
            }
        };
    }

    @Test
    void openSeaWithItsBedUndrawnHidesFarWaterButNotFarLand() {
        // Sea level in section 3, water down through section 2, the bed in section 1, which the near
        // renderer never compiled: it lay below the view from just over the water.
        SeamCoverage.Column sea = column(Set.of(3), Set.of(3, 2));
        assertEquals(SeamCoverage.Cover.WATER, SeamCoverage.cover(sea, 3, true, -4, 4));
        SeamCoverage seam = new SeamCoverage();
        seam.update(7, 2, SeamCoverage.cover(sea, 3, true, -4, 4), 1_000, false);
        seam.update(7, 2, SeamCoverage.cover(sea, 3, true, -4, 4), 1_000 + SeamCoverage.FADE_MILLIS, false);
        assertTrue(seam.waterCovered(7, 2), "the near water's surface is drawn: far water goes");
        assertEquals(0, seam.value(7, 2), "far land stays under the sea until the near bed is drawn");
        assertFalse(seam.fullyCovered(7, 2));
    }

    @Test
    void coverFollowsTheTopAndTheBed() {
        assertEquals(SeamCoverage.Cover.NONE, SeamCoverage.cover(column(Set.of(2), Set.of()), 3, false, -4, 4), "top not drawn yet");
        assertEquals(SeamCoverage.Cover.ALL, SeamCoverage.cover(column(Set.of(3, 2), Set.of()), 3, false, -4, 4), "land: the top is all");
        assertEquals(SeamCoverage.Cover.ALL, SeamCoverage.cover(column(Set.of(3, 1), Set.of(3, 2)), 3, true, -4, 4), "the bed drawn too");
        assertEquals(SeamCoverage.Cover.ALL, SeamCoverage.cover(column(Set.of(3), Set.of(3, 2, 1, 0, -1)), 3, true, -4, 4),
            "water deeper than four sections hides its bed");
        assertEquals(SeamCoverage.Cover.ALL, SeamCoverage.cover(column(Set.of(-4), Set.of(-4)), -4, true, -4, 4),
            "nothing under the world's floor to wait for");
    }

    @Test
    void landIceAndShallowWaterNeverWaitForTheGroundUnderThem() {
        // The near renderer compiles only sections it can see into: under a field, under the ice
        // and under a shallow lake's bed, the section below the top stays uncompiled for good.
        SeamCoverage.Column underground = column(Set.of(3), Set.of());
        assertEquals(SeamCoverage.Cover.ALL, SeamCoverage.cover(underground, 3, false, -4, 4), "land: its top section is all of it");
        SeamCoverage.Column frozen = column(Set.of(3), Set.of(2));
        assertEquals(SeamCoverage.Cover.ALL, SeamCoverage.cover(frozen, 3, false, -4, 4), "ice over a lake: far land goes from under the near ice");
        assertEquals(SeamCoverage.Cover.WATER, SeamCoverage.cover(frozen, 3, true, -4, 4), "open water over the same bed still waits for it");
    }

    @Test
    void theNearRenderersDrawPastTheRenderDistancesCircle() {
        // Render distance 8, the camera in the middle of chunk (0, 0).
        double x = 8.5;
        double z = 8.5;
        for (boolean sodium : new boolean[] {false, true}) {
            assertTrue(SeamCoverage.nearDrawsChunk(sodium, x, z, 0, 0, 8), "the camera's own chunk");
            assertTrue(SeamCoverage.nearDrawsChunk(sodium, x, z, 7, 0, 8), "inside the circle");
            assertTrue(SeamCoverage.nearDrawsChunk(sodium, x, z, 5, 5, 8), "a diagonal just past the old (r - 1) circle");
            assertFalse(SeamCoverage.nearDrawsChunk(sodium, x, z, 9, 0, 8), "past the render distance");
            assertFalse(SeamCoverage.nearDrawsChunk(sodium, x, z, 7, 7, 8), "the square's corner");
        }
        // Vanilla's rule is by chunk, less one on each axis: 8 chunks straight out is in, and so is (6, 6).
        assertTrue(SeamCoverage.nearDrawsChunk(false, x, z, 8, 0, 8));
        assertTrue(SeamCoverage.nearDrawsChunk(false, x, z, 6, 6, 8));
        assertTrue(SeamCoverage.nearDrawsChunk(false, x, z, 8, 4, 8));
        // Sodium's by blocks from the camera to the chunk's box, a block wider each side: chunk 8's
        // near side is 118.5 blocks off (in); chunk (8, 4)'s corner 118.5 and 54.5 blocks along the
        // axes, 130.4 blocks away (out).
        assertTrue(SeamCoverage.nearDrawsChunk(true, x, z, 8, 0, 8));
        assertFalse(SeamCoverage.nearDrawsChunk(true, x, z, 8, 4, 8));
        assertTrue(SeamCoverage.nearDrawsChunk(true, x, z, -8, 0, 8), "119.5 blocks off on the other side");
    }

    @Test
    void sectionsTooFarAboveOrBelowAreNotDrawn() {
        // From 70 blocks over the sea (y 136, section 8) at render distance 4, the sea's surface
        // section (3, y 48 to 63) is five sections down: neither renderer draws it, though it was
        // compiled while the camera stood lower.
        assertFalse(SeamCoverage.nearDrawsHeight(false, 136.0, 3, 4));
        assertFalse(SeamCoverage.nearDrawsHeight(true, 136.0, 3, 4), "71 blocks down to its box");
        assertTrue(SeamCoverage.nearDrawsHeight(false, 116.0, 3, 4), "from 50 up, four sections down");
        assertTrue(SeamCoverage.nearDrawsHeight(true, 116.0, 3, 4), "51 blocks down to its box");
        assertTrue(SeamCoverage.nearDrawsHeight(false, 136.0, 3, 8));
        assertTrue(SeamCoverage.nearDrawsHeight(true, 136.0, 3, 8));
    }

    @Test
    void waterUncoveredComesBackAtOnceWithTheLand() {
        SeamCoverage seam = new SeamCoverage();
        seam.update(1, 1, SeamCoverage.Cover.ALL, 0, true);
        assertTrue(seam.fullyCovered(1, 1));
        assertTrue(seam.waterCovered(1, 1));
        seam.update(1, 1, SeamCoverage.Cover.NONE, 10, false);
        assertEquals(0, seam.value(1, 1));
        assertEquals(0, seam.waterValue(1, 1), "unloaded: far water is back at once, no hole");
    }

    @Test
    void theTextureHoldsLandThenWaterPerColumn() {
        SeamCoverage seam = new SeamCoverage();
        seam.update(2, 3, SeamCoverage.Cover.WATER, 0, true);
        int slot = 2 + 3 * SeamCoverage.SIZE;
        assertEquals(0, seam.values()[slot * SeamCoverage.CHANNELS] & 0xFF);
        assertEquals(255, seam.values()[slot * SeamCoverage.CHANNELS + 1] & 0xFF);
        assertEquals(SeamCoverage.SIZE * SeamCoverage.SIZE * SeamCoverage.CHANNELS, seam.values().length);
    }

    @Test
    void landTheNearTerrainDrewBeforeAResetIsCoveredAtOnce() {
        SeamCoverage seam = new SeamCoverage();
        seam.update(3, 4, true, 1_000, true);
        assertTrue(seam.fullyCovered(3, 4), "no fade over near land already there");
        seam.update(5, 4, true, 1_000);
        assertFalse(seam.fullyCovered(5, 4), "a chunk drawn from now on fades as before");
    }
}
