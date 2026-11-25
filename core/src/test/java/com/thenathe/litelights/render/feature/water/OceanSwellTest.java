package com.thenathe.litelights.render.feature.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ocean waves' sea state and swell trains (OceanSwell), which include/water/waves.glsl adds up. */
class OceanSwellTest {
    private static final double TAU = Math.PI * 2.0;
    private static final double CALM = 0.04;
    private static final long AFTERNOON = 9000L;

    private static OceanSwell settled(double heading) {
        OceanSwell swell = new OceanSwell();
        swell.update(heading, CALM, CALM, AFTERNOON, 0.0);
        return swell;
    }

    private static double sumOfSquares(OceanSwell swell) {
        double sum = 0.0;
        for (int train = 0; train < OceanSwell.TRAINS; train++) {
            sum += swell.trainValue(train, 3) * swell.trainValue(train, 3);
        }
        return sum;
    }

    @Test
    void waveVectorsRepeatWithTheAnchor() {
        for (double heading = 0.0; heading < TAU; heading += 0.1) {
            OceanSwell swell = settled(heading);
            for (int train = 0; train < OceanSwell.TRAINS; train++) {
                for (int axis = 0; axis < 2; axis++) {
                    double cycles = swell.trainValue(train, axis) * 2048.0 / TAU;
                    assertEquals(Math.rint(cycles), cycles, 0.01, "whole cycles in 2048 blocks");
                }
            }
        }
    }

    @Test
    void theSwellTravelsWithTheWindAndKeepsItsHeight() {
        for (double heading = 0.0; heading < TAU; heading += 0.05) {
            OceanSwell swell = settled(heading);
            assertEquals(1.0, sumOfSquares(swell), 1.0e-5);
            double alongX = 0.0;
            double alongZ = 0.0;
            for (int train = 0; train < OceanSwell.TRAINS; train++) {
                alongX += swell.trainValue(train, 0) * swell.trainValue(train, 3);
                alongZ += swell.trainValue(train, 1) * swell.trainValue(train, 3);
            }
            double travel = Math.atan2(alongZ, alongX);
            assertEquals(0.0, Math.IEEEremainder(travel - heading, TAU), Math.toRadians(4.0), "heading " + heading);
        }
    }

    @Test
    void turningWindCrossfadesTheTrainsWithoutAJump() {
        double[] before = shares(settled(1.0));
        for (double heading = 1.0; heading < 1.0 + TAU; heading += 0.002) {
            double[] now = shares(settled(heading));
            for (int direction = 0; direction < now.length; direction++) {
                assertTrue(Math.abs(now[direction] - before[direction]) < 0.02, "heading " + heading);
            }
            before = now;
        }
    }

    /** Each of the sixteen directions' share of the swell, found by its wave vector's angle. */
    private static double[] shares(OceanSwell swell) {
        double[] shares = new double[16];
        for (int train = 0; train < OceanSwell.TRAINS; train++) {
            if (swell.trainValue(train, 3) > 0.0f) {
                double angle = Math.atan2(swell.trainValue(train, 1), swell.trainValue(train, 0));
                int direction = (int) Math.floorMod(Math.round(angle / (TAU / 16.0)), 16L);
                shares[direction] = swell.trainValue(train, 3);
            }
        }
        return shares;
    }

    @Test
    void phasesRunOnSmoothlyThroughTheHourlyWrap() {
        OceanSwell swell = settled(0.3);
        float lastShore = swell.shorePhase();
        float lastTrain = swell.trainValue(0, 2);
        // A little over an hour of the sea's clock in quarter seconds.
        for (int step = 0; step < 15_000; step++) {
            swell.update(0.3, CALM, CALM, AFTERNOON, 0.25);
            double shoreStep = swell.shorePhase() - lastShore;
            if (shoreStep < -1000.0) {
                shoreStep += TAU * 1024.0;
            }
            assertTrue(shoreStep > 0.0 && shoreStep < 0.5, "shore phase step " + shoreStep + " at " + step);
            double trainStep = Math.IEEEremainder(swell.trainValue(0, 2) - lastTrain, TAU);
            assertTrue(Math.abs(trainStep) < 0.5, "train phase step " + trainStep + " at " + step);
            lastShore = swell.shorePhase();
            lastTrain = swell.trainValue(0, 2);
        }
    }

    @Test
    void aHeldClockHoldsTheSea() {
        OceanSwell swell = settled(2.0);
        swell.update(2.0, CALM, CALM, AFTERNOON, 1.0);
        float shore = swell.shorePhase();
        float train = swell.trainValue(0, 2);
        swell.update(2.0, CALM, CALM, AFTERNOON, 0.0);
        assertEquals(shore, swell.shorePhase());
        assertEquals(train, swell.trainValue(0, 2));
    }

    @Test
    void stormsRaiseTheSeaAndAStillDawnCalmsIt() {
        float dawn = OceanSwell.targetSeaState(CALM, CALM, 0L);
        float afternoon = OceanSwell.targetSeaState(CALM, CALM, AFTERNOON);
        float rain = OceanSwell.targetSeaState(CALM, CALM + 0.074, AFTERNOON);
        float thunder = OceanSwell.targetSeaState(CALM, CALM + 0.135, AFTERNOON);
        float thunderAtDawn = OceanSwell.targetSeaState(CALM, CALM + 0.135, 0L);
        assertTrue(dawn < afternoon * 0.7, dawn + " " + afternoon);
        assertTrue(afternoon < rain && rain < thunder, afternoon + " " + rain + " " + thunder);
        assertTrue(thunderAtDawn > 0.7f, "a storm is rough at any hour");
        assertTrue(OceanSwell.targetSeaState(0.0, 0.0, 0L) >= 0.04f);
        assertTrue(OceanSwell.targetSeaState(0.06, 0.4, AFTERNOON) <= 1.0f);
    }

    @Test
    void theSeaStateEasesTowardTheWeather() {
        OceanSwell swell = settled(0.0);
        float calm = swell.seaState();
        swell.update(0.0, CALM, CALM + 0.135, AFTERNOON, 0.25);
        assertTrue(swell.seaState() > calm && swell.seaState() < calm + 0.05f, "a storm doesn't arrive in a frame");
        for (int step = 0; step < 400; step++) {
            swell.update(0.0, CALM, CALM + 0.135, AFTERNOON, 0.25);
        }
        assertEquals(OceanSwell.targetSeaState(CALM, CALM + 0.135, AFTERNOON), swell.seaState(), 0.01f, "settled after a hundred seconds");
    }
}
