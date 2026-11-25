package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.config.PreviewEvent;
import com.thenathe.litelights.config.PreviewTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When rainbows follow the rain, and how they come and go (RainbowSchedule). */
class RainbowScheduleTest {
    private static final long SEED = 0x5EEDL;
    private static final long MORNING = 1_000L;
    private static final long NIGHT = 18_000L;

    @Test
    void itGrowsInHoldsAndFadesOverThreeMinutes() {
        assertEquals(0.0f, RainbowSchedule.envelope(-1.0));
        assertEquals(0.0f, RainbowSchedule.envelope(0.0));
        assertEquals(0.5f, RainbowSchedule.envelope(RainbowSchedule.GROW_TICKS / 2.0), 1.0e-6f);
        assertEquals(1.0f, RainbowSchedule.envelope(RainbowSchedule.GROW_TICKS));
        assertEquals(1.0f, RainbowSchedule.envelope(RainbowSchedule.GROW_TICKS + RainbowSchedule.HOLD_TICKS - 1));
        assertEquals(0.5f, RainbowSchedule.envelope(RainbowSchedule.LIFE_TICKS - RainbowSchedule.FADE_TICKS / 2.0), 1.0e-6f);
        assertEquals(0.0f, RainbowSchedule.envelope(RainbowSchedule.LIFE_TICKS));
        assertEquals(3 * 60 * 20, RainbowSchedule.LIFE_TICKS);
    }

    @Test
    void twoInThreeRainsAndNineInTenThunderstormsLeaveOne() {
        int afterRain = 0;
        int afterThunder = 0;
        int slots = 20_000;
        for (long slot = 0; slot < slots; slot++) {
            long tick = slot * RainbowSchedule.SLOT_TICKS + 345;
            afterRain += RainbowSchedule.follows(SEED, tick, false) ? 1 : 0;
            afterThunder += RainbowSchedule.follows(SEED, tick, true) ? 1 : 0;
        }
        assertEquals(0.65, afterRain / (double) slots, 0.02);
        assertEquals(0.9, afterThunder / (double) slots, 0.02);
    }

    @Test
    void everyPlayerOfAWorldGetsTheSameAnswerWithinTheMinute() {
        for (long slot = 0; slot < 500; slot++) {
            long start = slot * RainbowSchedule.SLOT_TICKS;
            boolean first = RainbowSchedule.follows(SEED, start, false);
            assertEquals(first, RainbowSchedule.follows(SEED, start + RainbowSchedule.SLOT_TICKS - 1, false));
        }
        boolean differs = false;
        for (long slot = 0; slot < 50; slot++) {
            differs |= RainbowSchedule.follows(SEED, slot * 1200L, false) != RainbowSchedule.follows(SEED + 1, slot * 1200L, false);
        }
        assertTrue(differs, "another world has other rainbows");
    }

    /**
     * Over a day's clearings, the share that leave a rainbow anyone can see: at least half a minute
     * with the bow above the horizon (its top over 3 degrees) at half strength or more. With the sun
     * high, a bow only shows once the sun has sunk, or not before it fades.
     */
    @Test
    void aboutAThirdOfTheDaysClearingsShowARainbow() {
        double rainShare = visibleShare(false);
        double thunderShare = visibleShare(true);
        double overall = 0.7 * rainShare + 0.3 * thunderShare;
        assertTrue(overall > 0.28 && overall < 0.4, "rain " + rainShare + ", thunder " + thunderShare + ", overall " + overall);
    }

    private static double visibleShare(boolean thunderstorm) {
        int daytime = 0;
        int seen = 0;
        for (long cleared = 0; cleared < SkyEventSchedule.DAY_TICKS * 40L; cleared += 37) {
            long clock = Math.floorMod(cleared, SkyEventSchedule.DAY_TICKS);
            if (!RainbowSchedule.daytime(clock)) {
                continue;
            }
            daytime++;
            if (!RainbowSchedule.follows(SEED, cleared, thunderstorm)) {
                continue;
            }
            int visibleTicks = 0;
            for (int age = 0; age < RainbowSchedule.LIFE_TICKS; age += 20) {
                float sun = RainbowSchedule.sunElevation(clock + age);
                float strength = RainbowSchedule.envelope(age) * RainbowSchedule.sunLight(sun);
                if (strength >= 0.5f && Math.toDegrees(sun) < 42.0 - 3.0) {
                    visibleTicks += 20;
                }
            }
            seen += visibleTicks >= 600 ? 1 : 0;
        }
        return seen / (double) daytime;
    }

    @Test
    void theSunIsUpByDayAndDownAtNight() {
        assertTrue(RainbowSchedule.daytime(MORNING));
        assertTrue(RainbowSchedule.daytime(6_000L));
        assertFalse(RainbowSchedule.daytime(NIGHT));
        assertEquals(90.0, Math.toDegrees(RainbowSchedule.sunElevation(6_000L)), 0.5);
        assertEquals(0.0f, RainbowSchedule.sunLight(-0.02f));
        assertEquals(1.0f, RainbowSchedule.sunLight(0.1f));
    }

    /** A rain that clears in the slot the schedule says leaves a rainbow, else none. */
    private static long clearingWithRainbow(boolean wanted) {
        for (long tick = MORNING; ; tick += RainbowSchedule.SLOT_TICKS) {
            if (RainbowSchedule.follows(SEED, tick, false) == wanted && RainbowSchedule.daytime(tick)) {
                return tick;
            }
        }
    }

    private static void rainThenClear(RainbowSchedule.Watch watch, long clearAt, boolean rainsHere, long clock) {
        watch.tick(SEED, clearAt - 200, clock, 1.0f, 0.0f, rainsHere);
        watch.tick(SEED, clearAt - 50, clock, 0.4f, 0.0f, rainsHere);
        watch.tick(SEED, clearAt, clock, 0.1f, 0.0f, rainsHere);
    }

    @Test
    void theWatchStartsARainbowWhenRainClearsByDay() {
        long clearAt = clearingWithRainbow(true);
        RainbowSchedule.Watch watch = new RainbowSchedule.Watch();
        rainThenClear(watch, clearAt, true, Math.floorMod(clearAt, SkyEventSchedule.DAY_TICKS));
        assertEquals(1.0f, watch.strength(clearAt + 1000));
        assertEquals(0.0f, watch.strength(clearAt + RainbowSchedule.LIFE_TICKS));

        RainbowSchedule.Watch unlucky = new RainbowSchedule.Watch();
        long other = clearingWithRainbow(false);
        rainThenClear(unlucky, other, true, Math.floorMod(other, SkyEventSchedule.DAY_TICKS));
        assertEquals(0.0f, unlucky.strength(other + 1000), "not every clearing has one");
    }

    @Test
    void noneAtNightNorWhereItSnowedNorOnceTheRainReturns() {
        long clearAt = clearingWithRainbow(true);
        RainbowSchedule.Watch night = new RainbowSchedule.Watch();
        rainThenClear(night, clearAt, true, NIGHT);
        assertEquals(0.0f, night.strength(clearAt + 1000));

        RainbowSchedule.Watch snow = new RainbowSchedule.Watch();
        rainThenClear(snow, clearAt, false, MORNING);
        assertEquals(0.0f, snow.strength(clearAt + 1000), "snow clearing in a cold biome");

        RainbowSchedule.Watch again = new RainbowSchedule.Watch();
        rainThenClear(again, clearAt, true, Math.floorMod(clearAt, SkyEventSchedule.DAY_TICKS));
        assertTrue(again.strength(clearAt + 1000) > 0.0f);
        again.tick(SEED, clearAt + 1100, MORNING, 0.8f, 0.0f, true);
        assertEquals(0.0f, again.strength(clearAt + 1200), "the rain came back");

        RainbowSchedule.Watch left = new RainbowSchedule.Watch();
        rainThenClear(left, clearAt, true, Math.floorMod(clearAt, SkyEventSchedule.DAY_TICKS));
        left.reset();
        assertEquals(0.0f, left.strength(clearAt + 1000), "left the world");
    }

    @Test
    void theRainbowPreviewGoesWithTheDayAndTheOthersWithTheNight() {
        assertFalse(PreviewEvent.Event.RAINBOW.atNight());
        assertTrue(PreviewEvent.Event.RAINBOW.suits(PreviewTime.Time.NOON));
        assertTrue(PreviewEvent.Event.RAINBOW.suits(PreviewTime.Time.DUSK));
        assertFalse(PreviewEvent.Event.RAINBOW.suits(PreviewTime.Time.NIGHT));
        assertFalse(PreviewEvent.Event.RAINBOW.suits(null));
        assertTrue(PreviewEvent.Event.AURORA.suits(PreviewTime.Time.NIGHT));
        assertFalse(PreviewEvent.Event.AURORA.suits(PreviewTime.Time.NOON));
    }
}
