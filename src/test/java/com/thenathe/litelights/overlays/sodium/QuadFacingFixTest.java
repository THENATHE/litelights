package com.thenathe.litelights.overlays.sodium;

import com.thenathe.litelights.overlays.sodium.QuadFacingFix.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the face culling fix runs: only once it is in Sodium, while the setting wants it, until it fails. */
class QuadFacingFixTest {
    @BeforeEach
    void freshStart() {
        QuadFacingFix.resetForTesting();
    }

    @Test
    void runsOnlyOnceTheMixinIsInAndOnlyTestsTurnItOff() {
        assertFalse(QuadFacingFix.running(), "nothing runs before the plugin has looked at Sodium");
        QuadFacingFix.markApplied();
        assertTrue(QuadFacingFix.applied());
        assertTrue(QuadFacingFix.running(), "on by default");
        QuadFacingFix.setWantedForTesting(false);
        assertFalse(QuadFacingFix.running());
        QuadFacingFix.setWantedForTesting(true);
        assertTrue(QuadFacingFix.running());
    }

    @Test
    void neverRunsWhereItIsNotNeededOrNotSafe() {
        for (Status status : new Status[] {Status.NO_SODIUM, Status.NO_CONTINUITY, Status.FIXED_IN_SODIUM, Status.UNKNOWN_SODIUM}) {
            QuadFacingFix.checked(status, "test");
            QuadFacingFix.setWantedForTesting(true);
            assertFalse(QuadFacingFix.running(), status.name());
            assertEquals(status, QuadFacingFix.status());
        }
    }

    @Test
    void anErrorSwitchesItOffForTheRestOfTheSession() {
        QuadFacingFix.markApplied();
        QuadFacingFix.failed(new IllegalStateException("test"));
        assertEquals(Status.FAILED, QuadFacingFix.status());
        assertFalse(QuadFacingFix.running());
        QuadFacingFix.setWantedForTesting(true);
        QuadFacingFix.markApplied();
        assertFalse(QuadFacingFix.running(), "a failed fix stays off");
        QuadFacingFix.failed(new IllegalStateException("again"));
        assertEquals(Status.FAILED, QuadFacingFix.status());
    }

    @Test
    void countsOnlyWhileATestAsks() {
        QuadFacingFix.noteCopied(true);
        QuadFacingFix.startCountingForTesting();
        QuadFacingFix.noteCopied(true);
        QuadFacingFix.noteCopied(false);
        assertEquals("copied 2, refiled 1", QuadFacingFix.countsForTesting());
    }
}
