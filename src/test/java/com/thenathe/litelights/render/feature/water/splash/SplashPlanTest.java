package com.thenathe.litelights.render.feature.water.splash;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplashPlanTest {
    private static final float PLAYER_WIDTH = 0.6f;

    @Test
    void wadingInMakesNoSplash() {
        assertNull(SplashPlan.of(-0.1, PLAYER_WIDTH, 1.0f));
        assertFalse(SplashPlan.entered(false, true, -0.1));
    }

    @Test
    void enteringWaterFastEnoughSplashes() {
        assertTrue(SplashPlan.entered(false, true, -0.4));
        assertFalse(SplashPlan.entered(true, true, -0.4), "already in the water");
        assertFalse(SplashPlan.entered(false, false, -0.4), "still in the air");
        assertFalse(SplashPlan.entered(false, true, 0.4), "jumping out, not in");
    }

    @Test
    void higherFallsMakeBiggerSplashes() {
        SplashPlan oneBlock = SplashPlan.of(-0.4, PLAYER_WIDTH, 1.0f);
        SplashPlan tenBlocks = SplashPlan.of(-1.2, PLAYER_WIDTH, 1.0f);
        SplashPlan farFall = SplashPlan.of(-2.5, PLAYER_WIDTH, 1.0f);
        assertTrue(oneBlock.droplets() < tenBlocks.droplets());
        assertTrue(tenBlocks.droplets() < farFall.droplets());
        assertTrue(oneBlock.ringEnd() < tenBlocks.ringEnd());
        assertTrue(oneBlock.ripples() < farFall.ripples());
        assertEquals(0, oneBlock.columnDroplets());
        assertTrue(farFall.columnDroplets() > 0, "a hard landing throws a column of spray");
    }

    @Test
    void widerEntitiesMakeWiderRings() {
        SplashPlan player = SplashPlan.of(-1.0, PLAYER_WIDTH, 1.0f);
        SplashPlan horse = SplashPlan.of(-1.0, 1.4f, 1.0f);
        assertTrue(horse.ringEnd() > player.ringEnd());
    }

    @Test
    void reducedParticlesHalveTheDropletsAndNoneSkipsTheSplash() {
        SplashPlan full = SplashPlan.of(-1.2, PLAYER_WIDTH, 1.0f);
        SplashPlan reduced = SplashPlan.of(-1.2, PLAYER_WIDTH, 0.5f);
        assertEquals(full.droplets() / 2.0, reduced.droplets(), 1.0);
        assertNull(SplashPlan.of(-1.2, PLAYER_WIDTH, 0.0f));
    }

    @Test
    void dropletsAreCapped() {
        assertTrue(SplashPlan.of(-10.0, 3.0f, 1.0f).droplets() <= 90);
    }
}
