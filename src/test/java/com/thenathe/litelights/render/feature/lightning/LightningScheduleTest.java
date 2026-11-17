package com.thenathe.litelights.render.feature.lightning;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightningScheduleTest {
    private static final long SEED = 0x1234_5678_9ABCL;
    private static final int BEATS = 20_000;

    @Test
    void everyPlayerWorksOutTheSameFlash() {
        LightningSchedule.Flash first = new LightningSchedule.Flash();
        LightningSchedule.Flash second = new LightningSchedule.Flash();
        int found = 0;
        for (long beat = 0; beat < 2_000; beat++) {
            boolean one = LightningSchedule.flash(SEED, 3, -7, beat, 1.0f, first);
            boolean two = LightningSchedule.flash(SEED, 3, -7, beat, 1.0f, second);
            assertEquals(one, two);
            if (!one) {
                continue;
            }
            found++;
            assertEquals(first.startTick(), second.startTick());
            assertEquals(first.flickers(), second.flickers());
            for (double tick = first.startTick() - 2; tick < first.startTick() + first.length() + 2; tick += 0.25) {
                assertEquals(LightningSchedule.brightness(first, tick), LightningSchedule.brightness(second, tick));
                assertEquals(LightningSchedule.centreX(first, tick), LightningSchedule.centreX(second, tick));
            }
        }
        assertTrue(found > 100, "found " + found);
    }

    @Test
    void anotherWorldHasOtherFlashes() {
        LightningSchedule.Flash flash = new LightningSchedule.Flash();
        int same = 0;
        int both = 0;
        for (long beat = 0; beat < 5_000; beat++) {
            boolean here = LightningSchedule.flash(SEED, 0, 0, beat, 1.0f, flash);
            long tick = flash.startTick();
            boolean there = LightningSchedule.flash(SEED + 1, 0, 0, beat, 1.0f, flash);
            if (here && there) {
                both++;
                same += tick == flash.startTick() ? 1 : 0;
            }
        }
        assertTrue(both > 0);
        assertTrue(same < both / 10, same + " of " + both + " start together");
    }

    @Test
    void noThunderNoFlashes() {
        LightningSchedule.Flash flash = new LightningSchedule.Flash();
        assertEquals(0.0f, LightningSchedule.storminess(0.0f));
        assertEquals(0.0f, LightningSchedule.storminess(0.3f));
        assertEquals(1.0f, LightningSchedule.storminess(1.0f));
        for (long beat = 0; beat < BEATS; beat++) {
            assertFalse(LightningSchedule.flash(SEED, 1, 1, beat, 0.0f, flash));
        }
    }

    @Test
    void aStormFlashesEveryFewSecondsSomewhereAndLessOftenNearby() {
        LightningSchedule.Flash flash = new LightningSchedule.Flash();
        int cellBeatsWithFlash = 0;
        int quiet = 0;
        int longestQuiet = 0;
        for (long beat = 0; beat < BEATS; beat++) {
            boolean any = false;
            for (int cellX = -1; cellX <= 1; cellX++) {
                for (int cellZ = -1; cellZ <= 1; cellZ++) {
                    if (LightningSchedule.flash(SEED, cellX, cellZ, beat, 1.0f, flash)) {
                        cellBeatsWithFlash++;
                        any = true;
                    }
                }
            }
            quiet = any ? 0 : quiet + 1;
            longestQuiet = Math.max(longestQuiet, quiet);
        }
        double perCellBeat = cellBeatsWithFlash / (9.0 * BEATS);
        // The chance is 0.2 a beat at average liveliness; liveliness averages 1.
        assertTrue(perCellBeat > 0.17 && perCellBeat < 0.23, "per cell and beat " + perCellBeat);
        double secondsBetween = BEATS * LightningSchedule.BEAT_TICKS / 20.0 / cellBeatsWithFlash;
        assertTrue(secondsBetween > 1.0 && secondsBetween < 3.0, "a flash somewhere every " + secondsBetween + " s");
        assertTrue(longestQuiet >= 4, "a storm has its lulls: " + longestQuiet + " beats at most");
    }

    @Test
    void flashesFlickerOneToFourTimesAndFitInTwoBeats() {
        LightningSchedule.Flash flash = new LightningSchedule.Flash();
        int[] counts = new int[LightningSchedule.MAX_FLICKERS + 1];
        int spreading = 0;
        int flashes = 0;
        for (long beat = 0; beat < BEATS; beat++) {
            if (!LightningSchedule.flash(SEED, 2, 5, beat, 1.0f, flash)) {
                continue;
            }
            flashes++;
            counts[flash.flickers()]++;
            spreading += flash.spreads() ? 1 : 0;
            assertTrue(flash.startTick() >= beat * LightningSchedule.BEAT_TICKS && flash.startTick() < (beat + 1) * LightningSchedule.BEAT_TICKS);
            assertTrue(flash.startTick() + flash.length() < (beat + 2) * LightningSchedule.BEAT_TICKS, "the beat after is the last it reaches");
            double x = LightningSchedule.centreX(flash, flash.startTick());
            double z = LightningSchedule.centreZ(flash, flash.startTick());
            assertTrue(x >= 2 * LightningSchedule.CELL_BLOCKS && x < 3 * LightningSchedule.CELL_BLOCKS);
            assertTrue(z >= 5 * LightningSchedule.CELL_BLOCKS && z < 6 * LightningSchedule.CELL_BLOCKS);
        }
        assertEquals(0, counts[0]);
        for (int flickers = 1; flickers <= LightningSchedule.MAX_FLICKERS; flickers++) {
            assertTrue(counts[flickers] > 0, "some flashes flicker " + flickers + " times");
        }
        assertTrue(counts[2] + counts[3] > flashes / 2, "most flicker twice or three times");
        double spreadShare = spreading / (double) flashes;
        assertTrue(spreadShare > 0.28 && spreadShare < 0.42, "a third spread across the sky: " + spreadShare);
    }

    @Test
    void aFlashIsDarkBeforeAndAfterAndFlickersInBetween() {
        LightningSchedule.Flash flash = new LightningSchedule.Flash();
        int checked = 0;
        for (long beat = 0; checked < 200 && beat < BEATS; beat++) {
            if (!LightningSchedule.flash(SEED, -4, 9, beat, 1.0f, flash)) {
                continue;
            }
            checked++;
            assertEquals(0.0f, LightningSchedule.brightness(flash, flash.startTick() - 0.5));
            assertEquals(0.0f, LightningSchedule.brightness(flash, flash.startTick() + flash.length() + 0.5));
            float brightest = 0.0f;
            int rises = 0;
            float previous = 0.0f;
            boolean falling = false;
            for (double tick = flash.startTick(); tick <= flash.startTick() + flash.length(); tick += 0.05) {
                float now = LightningSchedule.brightness(flash, tick);
                assertTrue(now >= 0.0f && now <= 1.0f);
                brightest = Math.max(brightest, now);
                if (now > previous + 1.0e-4f && (falling || previous == 0.0f)) {
                    rises++;
                }
                falling = now < previous - 1.0e-4f;
                previous = now;
            }
            assertTrue(brightest > 0.6f, "the first flicker is bright: " + brightest);
            assertTrue(rises <= flash.flickers(), "it brightens once a flicker at most");
            if (flash.flickers() > 1) {
                assertTrue(rises > 1, "a double flicker is seen as two");
            }
            float radiusAtStart = LightningSchedule.radius(flash, flash.startTick());
            float radiusAtEnd = LightningSchedule.radius(flash, flash.startTick() + flash.length());
            assertTrue(radiusAtEnd >= radiusAtStart);
            if (!flash.spreads()) {
                assertEquals(LightningSchedule.centreX(flash, flash.startTick()), LightningSchedule.centreX(flash, flash.startTick() + 20));
            }
        }
        assertEquals(200, checked);
    }

    @Test
    void cellsAndBeatsSplitCleanlyEitherSideOfZero() {
        assertEquals(0, LightningSchedule.cellOf(0.0));
        assertEquals(0, LightningSchedule.cellOf(LightningSchedule.CELL_BLOCKS - 0.01));
        assertEquals(-1, LightningSchedule.cellOf(-0.01));
        assertEquals(1, LightningSchedule.cellOf(LightningSchedule.CELL_BLOCKS));
        assertEquals(0, LightningSchedule.beatOf(LightningSchedule.BEAT_TICKS - 1));
        assertEquals(-1, LightningSchedule.beatOf(-1));
        assertNotEquals(LightningSchedule.cellOf(-1.0), LightningSchedule.cellOf(1.0));
    }

    @Test
    void farFlashesCountForLess() {
        assertEquals(1.0f, CloudLightning.falloff(0.0f, 260.0f));
        assertEquals(0.5f, CloudLightning.falloff(260.0f, 260.0f), 1.0e-6f);
        assertTrue(CloudLightning.falloff(1000.0f, 260.0f) < 0.07f);
    }
}
