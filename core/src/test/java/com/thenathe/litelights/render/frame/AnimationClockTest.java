package com.thenathe.litelights.render.frame;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimationClockTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void runsOnRealTime() {
        AnimationClock clock = new AnimationClock();
        clock.advance(5 * SECOND, false);
        assertEquals(0.0, clock.seconds(), 1e-9);
        assertEquals(0.0f, clock.frameSeconds());
        clock.advance(5 * SECOND + SECOND / 4, false);
        assertEquals(0.25, clock.seconds(), 1e-9);
        assertEquals(0.25f, clock.frameSeconds(), 1e-6f);
    }

    @Test
    void standsStillWhilePausedAndGoesOnFromThere() {
        AnimationClock clock = new AnimationClock();
        clock.advance(0L, false);
        clock.advance(2 * SECOND, false);
        clock.advance(3 * SECOND, true);
        clock.advance(30 * SECOND, true);
        assertEquals(2.0, clock.seconds(), 1e-9);
        assertEquals(0.0f, clock.frameSeconds());
        // The first frame after the pause only counts the time since the last paused frame.
        clock.advance(30 * SECOND + SECOND / 2, false);
        assertEquals(2.5, clock.seconds(), 1e-9);
        assertEquals(0.5f, clock.frameSeconds(), 1e-6f);
    }

    @Test
    void wrapsAtAnHour() {
        AnimationClock clock = new AnimationClock();
        clock.advance(0L, false);
        clock.advance(3599 * SECOND, false);
        clock.advance(3601 * SECOND, false);
        assertEquals(1.0, clock.seconds(), 1e-6);
    }

    @Test
    void ignoresTimeRunningBackwards() {
        AnimationClock clock = new AnimationClock();
        clock.advance(10 * SECOND, false);
        clock.advance(9 * SECOND, false);
        assertEquals(0.0, clock.seconds(), 1e-9);
        assertEquals(0.0f, clock.frameSeconds());
    }
}
