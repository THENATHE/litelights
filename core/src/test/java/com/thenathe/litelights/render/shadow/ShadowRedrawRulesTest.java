package com.thenathe.litelights.render.shadow;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowRedrawRulesTest {
    private static final Vector3fc UP = new Vector3f(0, 1, 0);

    private static Vector3fc tilted(double degrees) {
        double radians = Math.toRadians(degrees);
        return new Vector3f((float) -Math.sin(radians), (float) Math.cos(radians), 0);
    }

    @Test
    void firstFrameDraws() {
        assertEquals(ShadowRedrawRules.Reason.FIRST_DRAW, ShadowRedrawRules.decide(null, UP, 0, 0, 0, 0, 256, false));
    }

    @Test
    void nothingChangedMeansNoRedraw() {
        assertEquals(ShadowRedrawRules.Reason.NONE, ShadowRedrawRules.decide(UP, UP, 0, 0, 3, -2, 256, false));
    }

    @Test
    void sunRedrawsOnlyAfterHalfADegree() {
        assertEquals(ShadowRedrawRules.Reason.NONE, ShadowRedrawRules.decide(UP, tilted(0.4), 0, 0, 0, 0, 256, false));
        assertEquals(ShadowRedrawRules.Reason.LIGHT_MOVED, ShadowRedrawRules.decide(UP, tilted(0.6), 0, 0, 0, 0, 256, false));
    }

    @Test
    void cameraMayDriftHalfTheMarginBeforeRecentering() {
        double limit = ShadowRedrawRules.recenterDistance(256);
        assertEquals(32.0, limit);
        assertEquals(ShadowRedrawRules.Reason.NONE, ShadowRedrawRules.decide(UP, UP, 0, 0, limit - 1, 0, 256, false));
        assertEquals(ShadowRedrawRules.Reason.CAMERA_MOVED, ShadowRedrawRules.decide(UP, UP, 0, 0, limit + 1, 0, 256, false));
    }

    @Test
    void coverageAlwaysReachesTheShadowDistanceFromTheCamera() {
        for (int distance = 64; distance <= 512; distance += 16) {
            double reach = ShadowRedrawRules.coverageRadius(distance) - ShadowRedrawRules.recenterDistance(distance);
            assertTrue(reach >= distance, "distance " + distance);
        }
    }

    @Test
    void casterChangesWaitLongerWhenFarAway() {
        long tenthOfASecond = 100_000_000L;
        assertFalse(ShadowRedrawRules.casterChangeIsDue(Double.POSITIVE_INFINITY, Long.MAX_VALUE));
        assertTrue(ShadowRedrawRules.casterChangeIsDue(10, tenthOfASecond));
        assertFalse(ShadowRedrawRules.casterChangeIsDue(200, tenthOfASecond));
        assertTrue(ShadowRedrawRules.casterChangeIsDue(200, 20 * tenthOfASecond));
    }

    @Test
    void casterChangesComeAfterCameraAndLight() {
        assertEquals(ShadowRedrawRules.Reason.CASTERS_CHANGED, ShadowRedrawRules.decide(UP, UP, 0, 0, 0, 0, 256, true));
        assertEquals(ShadowRedrawRules.Reason.LIGHT_MOVED, ShadowRedrawRules.decide(UP, tilted(2), 0, 0, 0, 0, 256, true));
    }
}
