package com.thenathe.litelights.render.feature.water.splash;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedSplashPlanTest {
    private static final float PLAYER_WIDTH = 0.6f;

    @Test
    void wadingInAndParticlesOffMakeNoSplash() {
        assertNull(AdvancedSplashPlan.of(-0.1, PLAYER_WIDTH, 1.0f));
        assertNull(AdvancedSplashPlan.of(-1.0, PLAYER_WIDTH, 0.0f));
    }

    @Test
    void harderLandingsSplashBiggerAndHigher() {
        AdvancedSplashPlan hop = AdvancedSplashPlan.of(-0.4, PLAYER_WIDTH, 1.0f);
        AdvancedSplashPlan fall = AdvancedSplashPlan.of(-1.2, PLAYER_WIDTH, 1.0f);
        AdvancedSplashPlan dive = AdvancedSplashPlan.of(-1.9, PLAYER_WIDTH, 1.0f);
        assertTrue(hop.ringReach() < fall.ringReach() && fall.ringReach() <= dive.ringReach());
        assertTrue(hop.crownPeak() < fall.crownPeak() && fall.crownPeak() <= dive.crownPeak());
        assertTrue(hop.droplets() < dive.droplets());
        assertFalse(hop.secondRing());
        assertTrue(dive.secondRing());
    }

    @Test
    void theSpritesAlwaysHoldTheSplash() {
        for (double speed = 0.15; speed < 4.0; speed += 0.05) {
            for (float width : new float[] {0.3f, PLAYER_WIDTH, 1.4f, 4.0f}) {
                AdvancedSplashPlan plan = AdvancedSplashPlan.of(-speed, width, 1.0f);
                assertTrue(plan.ringReach() > 0.0f && plan.ringReach() <= AdvancedSplashPlan.RING_REACH_LIMIT);
                assertTrue(plan.crownPeak() >= 1 && plan.crownPeak() < AdvancedSplashPlan.CROWN_FRAMES);
                assertTrue(plan.crownRiseTicks() > 0 && plan.crownFallTicks() > 0);
            }
        }
    }

    @Test
    void sparseParticlesThinOnlyTheDroplets() {
        AdvancedSplashPlan full = AdvancedSplashPlan.of(-1.2, PLAYER_WIDTH, 1.0f);
        AdvancedSplashPlan sparse = AdvancedSplashPlan.of(-1.2, PLAYER_WIDTH, 0.4f);
        assertTrue(sparse.droplets() < full.droplets());
        assertTrue(sparse.ringReach() == full.ringReach() && sparse.crownPeak() == full.crownPeak());
    }
}
