package com.thenathe.litelights.render.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShadowDepthTest {
    @Test
    void noExtraDarknessKeepsTodaysLook() {
        assertEquals(0.46f, ShadowDepth.forExtraDarkness(0.0f), 1.0e-6f);
    }

    @Test
    void fullExtraDarknessIsNearlyBlackInFullSun() {
        assertEquals(0.96f, ShadowDepth.forExtraDarkness(1.0f), 1.0e-6f);
    }

    @Test
    void halfwayIsAlreadyDarkerThanTheOldMaximum() {
        // The old slider stopped at 0.8 at 100 %.
        assertTrue(ShadowDepth.forExtraDarkness(0.5f) > 0.8f);
    }

    @Test
    void outOfRangeValuesAreClamped() {
        assertEquals(ShadowDepth.forExtraDarkness(0.0f), ShadowDepth.forExtraDarkness(-1.0f));
        assertEquals(ShadowDepth.forExtraDarkness(1.0f), ShadowDepth.forExtraDarkness(3.0f));
    }

    @Test
    void curveRisesSmoothlyWithEvenStepsInKeptLight() {
        float previous = ShadowDepth.forExtraDarkness(0.0f);
        double previousRatio = Double.NaN;
        for (int step = 1; step <= 20; step++) {
            float depth = ShadowDepth.forExtraDarkness(step / 20.0f);
            assertTrue(depth > previous, "rises at step " + step);
            // Each 5 % step keeps the same fraction of the remaining light: no kinks.
            double ratio = (1.0 - depth) / (1.0 - previous);
            if (!Double.isNaN(previousRatio)) {
                assertEquals(previousRatio, ratio, 1.0e-4);
            }
            previousRatio = ratio;
            previous = depth;
        }
    }
}
