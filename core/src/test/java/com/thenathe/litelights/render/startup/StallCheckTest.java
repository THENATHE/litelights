package com.thenathe.litelights.render.startup;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StallCheckTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void framesUnderTheLimitAreFine() {
        StallCheck check = new StallCheck(10 * SECOND);
        long frameStart = 1_000 * SECOND;
        assertFalse(check.shouldWarn(frameStart + SECOND, frameStart));
        assertFalse(check.shouldWarn(frameStart + 10 * SECOND, frameStart));
    }

    @Test
    void aFramePastTheLimitWarnsOnce() {
        StallCheck check = new StallCheck(10 * SECOND);
        long frameStart = 1_000 * SECOND;
        assertTrue(check.shouldWarn(frameStart + 11 * SECOND, frameStart));
        // Still stuck: no second warning.
        assertFalse(check.shouldWarn(frameStart + 60 * SECOND, frameStart));
    }

    @Test
    void onlyOneWarningPerSession() {
        StallCheck check = new StallCheck(10 * SECOND);
        long first = 1_000 * SECOND;
        assertTrue(check.shouldWarn(first + 11 * SECOND, first));
        long later = first + 20 * SECOND;
        assertFalse(check.shouldWarn(later + 30 * SECOND, later));
    }

    @Test
    void nanoTimeMayBeNegative() {
        // System.nanoTime has an arbitrary origin; only differences count.
        StallCheck check = new StallCheck(10 * SECOND);
        long frameStart = Long.MIN_VALUE + SECOND;
        assertFalse(check.shouldWarn(frameStart + 5 * SECOND, frameStart));
        assertTrue(check.shouldWarn(frameStart + 12 * SECOND, frameStart));
    }

    @Test
    void timesAreWrittenPlainly() {
        assertEquals("81.2 s", StartupTrace.seconds(81_234_000_000L));
        assertEquals("0.0 s", StartupTrace.seconds(12_000_000L));
        assertEquals(1_234, StartupTrace.millis(1_234_567_890L));
    }
}
