package com.thenathe.litelights.render.frame;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeFlowTest {
    private static final float FRAME = 1.0f / 60.0f;

    /** A minute of normal play at 60 frames a second: a tick every third frame, now and then two. */
    @Test
    void normalPlayIsNeverHurried() {
        TimeFlow flow = new TimeFlow();
        long day = 1_000L;
        long game = 50_000L;
        for (int frame = 0; frame < 3600; frame++) {
            if (frame % 3 == 0) {
                day++;
                game++;
            }
            if (frame % 97 == 0) {
                // A server sync a few ticks ahead.
                day += 3;
                game += 3;
            }
            flow.update(true, day, game, 20.0f, false, FRAME);
            assertFalse(flow.hurried(), "frame " + frame);
            assertEquals(1.0f, flow.speed());
        }
    }

    @Test
    void daylightCycleOffIsNotAJump() {
        TimeFlow flow = new TimeFlow();
        for (int frame = 0; frame < 600; frame++) {
            flow.update(true, 6_000L, 50_000L + frame / 3, 20.0f, false, FRAME);
            assertFalse(flow.hurried());
        }
    }

    @Test
    void timeSetIsAJumpForOneFrame() {
        TimeFlow flow = new TimeFlow();
        flow.update(true, 6_000L, 50_000L, 20.0f, false, FRAME);
        flow.update(true, 6_001L, 50_001L, 20.0f, false, FRAME);
        assertFalse(flow.jumped());
        flow.update(true, 12_000L, 50_001L, 20.0f, false, FRAME);
        assertTrue(flow.jumped());
        assertTrue(flow.hurried());
        assertFalse(flow.racing());
        flow.update(true, 12_000L, 50_001L, 20.0f, false, FRAME);
        assertFalse(flow.jumped());
        // Still hurried for half a second, while the sky catches up with the new time.
        assertTrue(flow.jumpedRecently());
        for (int frame = 0; frame < 25; frame++) {
            flow.update(true, 12_000L, 50_001L, 20.0f, false, FRAME);
        }
        assertTrue(flow.hurried());
        for (int frame = 0; frame < 10; frame++) {
            flow.update(true, 12_000L, 50_001L, 20.0f, false, FRAME);
        }
        assertFalse(flow.hurried());
        // Backwards too.
        flow.update(true, 1_000L, 50_002L, 20.0f, false, FRAME);
        assertTrue(flow.jumped());
    }

    @Test
    void aFasterTickRateRaces() {
        TimeFlow flow = new TimeFlow();
        long game = 0L;
        for (int frame = 0; frame < 120; frame++) {
            game += frame % 3 == 0 ? 2 : 1;
            flow.update(true, game, game, 100.0f, false, FRAME);
        }
        assertTrue(flow.racing());
        assertTrue(flow.speed() >= 5.0f);
    }

    @Test
    void aSprintRacesFromHowFastTheTimeArrives() {
        TimeFlow flow = new TimeFlow();
        long game = 0L;
        for (int frame = 0; frame < 120; frame++) {
            // The server's time arrives 40 ticks a frame, the client's tick rate stays 20.
            game += 40;
            flow.update(true, game, game, 20.0f, false, FRAME);
        }
        assertTrue(flow.racing());
        assertEquals(40.0f * 60.0f / 20.0f, flow.speed(), 1.0f);
        // Once the sprint ends, the next half second measures the normal pace again.
        for (int frame = 0; frame < 90; frame++) {
            game += frame % 3 == 0 ? 1 : 0;
            flow.update(true, game, game, 20.0f, false, FRAME);
        }
        assertFalse(flow.racing());
        assertEquals(1.0f, flow.speed());
    }

    @Test
    void frozenTicksDoNotRace() {
        TimeFlow flow = new TimeFlow();
        for (int frame = 0; frame < 120; frame++) {
            flow.update(true, 6_000L, 50_000L, 100.0f, true, FRAME);
        }
        assertFalse(flow.racing());
        assertEquals(1.0f, flow.speed());
    }

    @Test
    void pausedFramesMeasureNothingAndANewLevelStartsAfresh() {
        TimeFlow flow = new TimeFlow();
        flow.update(true, 6_000L, 50_000L, 20.0f, false, FRAME);
        for (int frame = 0; frame < 600; frame++) {
            flow.update(true, 6_000L, 50_000L, 20.0f, false, 0.0f);
        }
        assertFalse(flow.hurried());
        // A level with another time: the first frame only sets the baseline.
        flow.update(false, 0L, 0L, 20.0f, false, FRAME);
        flow.update(true, 18_000L, 900L, 20.0f, false, FRAME);
        assertFalse(flow.jumped());
    }
}
