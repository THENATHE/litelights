package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** How much of the season's snow far land shows: the cover, from Almanac's depths (FarSnow). */
class FarSnowCoverTest {
    @Test
    void formingSnowIsAsFarAlongAsTheClimateThatIsPartCovered() {
        // HOT never snows, TEMPERATE is 40 % covered, COLD has long been white.
        assertEquals(0.4, FarSnow.formingCover(new double[] {0.0, 0.4, 3.0}));
        // Nothing part way: no snow yet, all covered, or depths that haven't arrived (-1).
        assertEquals(-1.0, FarSnow.formingCover(new double[] {0.0, 0.0, 1.0}));
        assertEquals(-1.0, FarSnow.formingCover(new double[] {-1.0, -1.0, -1.0}));
        assertEquals(-1.0, FarSnow.formingCover(new double[] {0.0, 1.0, 2.0}));
        // Two climates forming: the one that is further behind.
        assertEquals(0.25, FarSnow.formingCover(new double[] {0.0, 0.25, 0.75}));
    }

    @Test
    void theCoverShownMovesSmoothlyBetweenTheSixteenthsThatArrive() {
        double step = 1.0 / 16.0;
        // A tick moves it a little, never past the target, and the 256 ticks a sixteenth takes are plenty.
        assertEquals(0.25 + FarSnow.COVER_EASE_PER_TICK, FarSnow.easedCover(0.25, 0.25 + step, 1.0), 1.0e-9);
        assertEquals(0.25 + step, FarSnow.easedCover(0.25, 0.25 + step, 256.0), 1.0e-9);
        assertEquals(0.25, FarSnow.easedCover(0.25, 0.25, 40.0), 1.0e-9);
        // Joining part way through, or a depth set by command, is shown at once.
        assertEquals(0.9, FarSnow.easedCover(0.1, 0.9, 1.0), 1.0e-9);
    }

    @Test
    void theCoverIsTheShareOfSnowBothWays() {
        // Forming or melting, the climate part way gives the share.
        assertEquals(0.4, FarSnow.bareCoverTarget(0.3, 0.4, 0.0, false));
        assertEquals(0.4, FarSnow.bareCoverTarget(0.5, 0.4, 0.0, false));
        // Nothing part way on joining, or after a snowfall filled in: the lines hold all of their snow.
        assertEquals(1.0, FarSnow.bareCoverTarget(-1.0, -1.0, 0.0, false));
        assertEquals(1.0, FarSnow.bareCoverTarget(15.0 / 16.0, -1.0, 0.0, false));
        // Melted away: the last of it holds until the lines without it have had time to arrive and fill in.
        assertEquals(1.0 / 16.0, FarSnow.bareCoverTarget(1.0 / 16.0, -1.0, 20.0, false));
        assertEquals(1.0 / 16.0, FarSnow.bareCoverTarget(1.0 / 16.0, -1.0, FarSnow.COVER_HOLD_TICKS, true));
        assertEquals(1.0, FarSnow.bareCoverTarget(1.0 / 16.0, -1.0, FarSnow.COVER_HOLD_TICKS, false));
    }
}
