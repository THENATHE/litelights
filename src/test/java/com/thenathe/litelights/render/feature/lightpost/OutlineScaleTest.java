package com.thenathe.litelights.render.feature.lightpost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutlineScaleTest {
    /** The level's projection scale at vanilla's default 70 degree field of view, the hand's own. */
    private static final float DEFAULT_FOV_SCALE = (float) (1.0 / Math.tan(Math.toRadians(70.0) / 2.0));

    @Test
    void atTheDefaultFieldOfViewTheReferenceIsTheHandItemDistance() {
        assertEquals(OutlineScale.HAND_ITEM_DISTANCE, OutlineScale.referenceDistance(DEFAULT_FOV_SCALE), 1.0e-5f);
    }

    @Test
    void aNarrowerFieldOfViewMakesThingsShowBiggerSoTheReferenceMovesOut() {
        float zoomed = (float) (1.0 / Math.tan(Math.toRadians(30.0) / 2.0));
        float wide = (float) (1.0 / Math.tan(Math.toRadians(110.0) / 2.0));
        assertTrue(OutlineScale.referenceDistance(zoomed) > OutlineScale.HAND_ITEM_DISTANCE);
        assertTrue(OutlineScale.referenceDistance(wide) < OutlineScale.HAND_ITEM_DISTANCE);
    }

    @Test
    void theOutlineShrinksInProportionToTheDistance() {
        float reference = OutlineScale.HAND_ITEM_DISTANCE;
        float sigma = 8.0f;
        assertEquals(sigma * reference / 3.0f, OutlineScale.sigma(sigma, 3.0f, reference), 1.0e-5f);
        assertEquals(sigma * reference / 6.0f, OutlineScale.sigma(sigma, 6.0f, reference), 1.0e-5f);
        // Twice as far, half as wide.
        assertEquals(OutlineScale.sigma(sigma, 3.0f, reference) / 2.0f, OutlineScale.sigma(sigma, 6.0f, reference), 1.0e-5f);
    }

    @Test
    void scalingIsTheSameWhateverTheConfiguredSize() {
        // Sizes whose three-block outline is still above the thinnest one.
        float reference = OutlineScale.HAND_ITEM_DISTANCE;
        for (float sigma : new float[] {4.0f, 8.0f, 15.0f, 40.0f}) {
            assertEquals(reference / 3.0f, OutlineScale.sigma(sigma, 3.0f, reference) / sigma, 1.0e-5f);
        }
    }

    @Test
    void nothingCloserThanTheHandItemGetsAWiderOutlineThanFirstPerson() {
        float reference = OutlineScale.HAND_ITEM_DISTANCE;
        assertEquals(8.0f, OutlineScale.sigma(8.0f, 0.3f, reference), 1.0e-6f);
        assertEquals(8.0f, OutlineScale.sigma(8.0f, reference, reference), 1.0e-6f);
    }

    @Test
    void farAwayTheOutlineStopsAtTheThinnestThatStillShows() {
        float reference = OutlineScale.HAND_ITEM_DISTANCE;
        assertEquals(OutlineScale.MIN_SIGMA, OutlineScale.sigma(8.0f, 64.0f, reference), 1.0e-6f);
        // A first-person outline set thinner than that floor is never made wider.
        assertEquals(0.5f, OutlineScale.sigma(0.5f, 64.0f, reference), 1.0e-6f);
    }

    @Test
    void theOutlineNeverGrowsWithDistance() {
        float reference = OutlineScale.HAND_ITEM_DISTANCE;
        float previous = Float.MAX_VALUE;
        for (float distance = 0.1f; distance < 80.0f; distance += 0.25f) {
            float sigma = OutlineScale.sigma(10.0f, distance, reference);
            assertTrue(sigma <= previous, "wider at " + distance);
            previous = sigma;
        }
    }
}
