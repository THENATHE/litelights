package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A weather mod's wind, known only by its velocity at any tick, summed into the drift the clouds
 * ride: the drift's rate is the wind, it agrees with a fine sum of the same wind, and it is the same
 * whatever order it is asked in, so every player gets the same sky.
 */
class IntegratedWindTest {
    /**
     * A wind like a weather mod's: a heading that wanders as three slow waves of days and a speed
     * that swings, with no closed-form drift.
     */
    private static void wanderingWind(double tick, double[] out) {
        double day = 24_000.0;
        double heading = 1.3 + 1.6 * Math.sin(2.0 * Math.PI * tick / (7.3 * day) + 0.4)
            + 1.0 * Math.sin(2.0 * Math.PI * tick / (17.9 * day) + 2.1)
            + 0.45 * Math.sin(2.0 * Math.PI * tick / (2.9 * day) + 5.0);
        double speed = 0.07 * (1.0 + 0.25 * Math.sin(2.0 * Math.PI * tick / (5.3 * day) + 1.0));
        out[0] = Math.cos(heading) * speed;
        out[1] = Math.sin(heading) * speed;
    }

    /** The same wind summed by Simpson's rule, a tick a step. */
    private static double[] fineSum(double to) {
        if (to <= 0.0) {
            return new double[] {0.0, 0.0};
        }
        double[] at = new double[2];
        double x = 0.0;
        double z = 0.0;
        int steps = (int) Math.ceil(to);
        steps += steps % 2;
        double step = to / steps;
        for (int i = 0; i <= steps; i++) {
            wanderingWind(i * step, at);
            double weight = i == 0 || i == steps ? 1.0 : (i % 2 == 1 ? 4.0 : 2.0);
            x += weight * at[0];
            z += weight * at[1];
        }
        return new double[] {x * step / 3.0, z * step / 3.0};
    }

    @Test
    void theDriftIsTheWindSummedUp() {
        IntegratedWind wind = new IntegratedWind(IntegratedWindTest::wanderingWind);
        for (double tick : new double[] {0.0, 1.0, 5_999.5, 12_000.0, 37_123.25, 250_000.0, 1_000_003.0}) {
            double[] expected = fineSum(tick);
            assertEquals(expected[0], wind.driftX(tick), 1.0e-6 + Math.abs(expected[0]) * 1.0e-9, "x at " + tick);
            assertEquals(expected[1], wind.driftZ(tick), 1.0e-6 + Math.abs(expected[1]) * 1.0e-9, "z at " + tick);
        }
    }

    @Test
    void theDriftMovesAtTheWindsSpeedWithNoStepBetweenSegments() {
        IntegratedWind wind = new IntegratedWind(IntegratedWindTest::wanderingWind);
        double[] velocity = new double[2];
        for (double tick : new double[] {11_999.0, 12_000.0, 24_000.0 - 0.25, 600_000.0, 600_000.0 + 0.5}) {
            double before = wind.driftX(tick - 0.25);
            double after = wind.driftX(tick + 0.25);
            wanderingWind(tick, velocity);
            // Within a tick the drift is drawn straight, at the tick's mean wind: within a hundred-thousandth of a block a tick.
            assertEquals(velocity[0], (after - before) / 0.5, 1.0e-5, "x rate at " + tick);
            before = wind.driftZ(tick - 0.25);
            after = wind.driftZ(tick + 0.25);
            assertEquals(velocity[1], (after - before) / 0.5, 1.0e-5, "z rate at " + tick);
        }
    }

    @Test
    void everyPlayerGetsTheSameDriftWhateverTheyAskedBefore() {
        IntegratedWind early = new IntegratedWind(IntegratedWindTest::wanderingWind);
        IntegratedWind late = new IntegratedWind(IntegratedWindTest::wanderingWind);
        late.prepare(3_000_000.0);
        early.driftX(10.0);
        early.driftX(1_500_000.0);
        for (double tick : new double[] {2_345_678.9, 12_000.0 * 77, 5.0}) {
            assertEquals(Double.doubleToLongBits(late.driftX(tick)), Double.doubleToLongBits(early.driftX(tick)), "x at " + tick);
            assertEquals(Double.doubleToLongBits(late.driftZ(tick)), Double.doubleToLongBits(early.driftZ(tick)), "z at " + tick);
        }
    }

    @Test
    void preparingSumsAhead() {
        IntegratedWind wind = new IntegratedWind(IntegratedWindTest::wanderingWind);
        assertFalse(wind.preparedTo(100_000.0));
        wind.prepare(100_000.0);
        assertTrue(wind.preparedTo(100_000.0));
        assertTrue(wind.preparedTo(50_000.0));
    }

    @Test
    void beforeTickZeroTheCloudsHaventMoved() {
        IntegratedWind wind = new IntegratedWind(IntegratedWindTest::wanderingWind);
        assertEquals(0.0, wind.driftX(-50.0));
        assertEquals(0.0, wind.driftZ(0.0));
    }
}
