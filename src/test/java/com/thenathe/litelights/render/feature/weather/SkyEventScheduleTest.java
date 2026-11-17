package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.config.PreviewEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyEventScheduleTest {
    private static final long SEED = 0x1234_5678_9ABCL;

    @Test
    void aNightRunsFromNoonToNoon() {
        assertEquals(0, SkyEventSchedule.nightIndex(6000));
        assertEquals(0, SkyEventSchedule.nightIndex(18000));
        assertEquals(0, SkyEventSchedule.nightIndex(24000 + 2000), "the small hours belong to the night before");
        assertEquals(1, SkyEventSchedule.nightIndex(24000 + 6000));
        assertEquals(-1, SkyEventSchedule.nightIndex(1000));
        assertEquals(5, SkyEventSchedule.nightIndex(5 * 24000 + 18000));
    }

    @Test
    void theSameSeedAndNightAlwaysGiveTheSamePlan() {
        for (long night = 0; night < 50; night++) {
            assertEquals(SkyEventSchedule.night(SEED, night), SkyEventSchedule.night(SEED, night));
        }
    }

    @Test
    void anotherWorldHasOtherNights() {
        int differences = 0;
        for (long night = 0; night < 50; night++) {
            if (!SkyEventSchedule.night(SEED, night).equals(SkyEventSchedule.night(SEED + 1, night))) {
                differences++;
            }
        }
        assertTrue(differences > 40);
    }

    @Test
    void eventsComeOnSomeNightsAtAboutTheIntendedRates() {
        int auroras = 0;
        int showers = 0;
        int fireworks = 0;
        int nights = 20000;
        for (long night = 0; night < nights; night++) {
            SkyEventSchedule.Night plan = SkyEventSchedule.night(SEED, night);
            auroras += plan.auroraStrength() > 0 ? 1 : 0;
            showers += plan.meteorShower() ? 1 : 0;
            fireworks += plan.fireworks() ? 1 : 0;
            if (plan.auroraStrength() > 0) {
                assertTrue(plan.auroraStrength() >= 0.45f && plan.auroraStrength() <= 1.0f);
            }
        }
        assertEquals(SkyEventSchedule.AURORA_CHANCE, auroras / (double) nights, 0.02);
        assertEquals(SkyEventSchedule.METEOR_SHOWER_CHANCE, showers / (double) nights, 0.01);
        assertEquals(SkyEventSchedule.FIREWORKS_CHANCE, fireworks / (double) nights, 0.01);
    }

    @Test
    void auroraStrengthCoversItsWholeRange() {
        float weakest = 1.0f;
        float strongest = 0.0f;
        for (long night = 0; night < 2000; night++) {
            float strength = SkyEventSchedule.night(SEED, night).auroraStrength();
            if (strength > 0) {
                weakest = Math.min(weakest, strength);
                strongest = Math.max(strongest, strength);
            }
        }
        assertTrue(weakest < 0.5f && strongest > 0.95f, "from faint to bright: " + weakest + " to " + strongest);
    }

    @Test
    void burstsAreFixedByBeat() {
        SkyEventSchedule.Night night = SkyEventSchedule.night(SEED, 7);
        float[] first = new float[5];
        float[] second = new float[5];
        for (long beat = 0; beat < 500; beat++) {
            int bursts = SkyEventSchedule.volleySize(SEED, night, beat);
            assertEquals(bursts, SkyEventSchedule.volleySize(SEED, night, beat));
            for (int burst = 0; burst < bursts; burst++) {
                assertEquals(SkyEventSchedule.burstTick(SEED, night, beat, burst), SkyEventSchedule.burstTick(SEED, night, beat, burst));
                SkyEventSchedule.firework(SEED, night, beat, burst, first);
                SkyEventSchedule.firework(SEED, night, beat, burst, second);
                assertArrayEquals(first, second);
            }
        }
    }

    @Test
    void fireworksStayLowOverTheHorizonSmallAndInTheEvening() {
        SkyEventSchedule.Night night = SkyEventSchedule.night(SEED, 3);
        float[] burst = new float[5];
        for (long beat = 0; beat < 500; beat++) {
            for (int index = 0; index < SkyEventSchedule.volleySize(SEED, night, beat); index++) {
                SkyEventSchedule.firework(SEED, night, beat, index, burst);
                assertTrue(burst[1] > Math.toRadians(6.0) && burst[1] < Math.toRadians(22.0));
                assertTrue(burst[2] >= Math.toRadians(3.6) - 1e-6 && burst[2] <= Math.toRadians(6.6) + 1e-6,
                    "0.9.8's bursts are about 60 % of 0.9.7's 6 to 11 degrees: " + Math.toDegrees(burst[2]));
                assertTrue(burst[3] >= 0 && burst[3] < 6);
            }
        }
        assertTrue(SkyEventSchedule.fireworksHour(13_500));
        assertTrue(SkyEventSchedule.fireworksHour(3 * 24000 + 15_000));
        assertTrue(!SkyEventSchedule.fireworksHour(6_000));
        assertTrue(!SkyEventSchedule.fireworksHour(20_000));
    }

    @Test
    void aVolleyStaysInsideItsBeat() {
        for (SkyEventSchedule.Night night : List.of(SkyEventSchedule.night(SEED, 3),
                SkyEventSchedule.preview(3, PreviewEvent.Event.FIREWORKS, 0.0f, 0.0f))) {
            int beatTicks = SkyEventSchedule.beatTicks(night);
            for (long beat = -50; beat < 2000; beat++) {
                int bursts = SkyEventSchedule.volleySize(SEED, night, beat);
                assertTrue(bursts >= 1 && bursts <= SkyEventSchedule.MAX_VOLLEY);
                long previous = Long.MIN_VALUE;
                for (int burst = 0; burst < bursts; burst++) {
                    long tick = SkyEventSchedule.burstTick(SEED, night, beat, burst);
                    assertTrue(tick >= beat * beatTicks && tick < (beat + 1) * beatTicks, "burst " + burst + " of beat " + beat);
                    assertTrue(tick > previous);
                    previous = tick;
                }
            }
        }
    }

    @Test
    void aRealNightHasMostlySingleBurstsSecondsApart() {
        Show show = show(SkyEventSchedule.night(SEED, 3), 20_000);
        double longest = show.pauses.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double shortest = show.pauses.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double average = show.pauses.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        long usual = show.pauses.stream().filter(pause -> pause >= 4.0 && pause <= 12.0).count();
        assertTrue(shortest >= 2.5 && longest <= 13.5 && longest > 12.0, "pauses from " + shortest + " to " + longest + " seconds");
        assertEquals(8.8, average, 0.3, "about nine seconds between volleys on average");
        assertTrue(usual > 0.85 * show.pauses.size(), "most pauses are 4 to 12 seconds: " + usual + " of " + show.pauses.size());
        assertTrue(show.followers.stream().allMatch(gap -> gap >= 0.4 && gap <= 1.0), "a pair or three follow each other quickly");
        double singles = show.singles / (double) show.volleys;
        assertEquals(1.0 - SkyEventSchedule.PAIR_CHANCE - SkyEventSchedule.TRIPLE_CHANCE, singles, 0.02, "mostly single bursts");
    }

    @Test
    void aPreviewIsBusierButStillPauses() {
        Show show = show(SkyEventSchedule.preview(9, PreviewEvent.Event.FIREWORKS, 0.0f, 0.0f), 5_000);
        double average = show.pauses.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        assertTrue(average > 3.0 && average < 4.5, "about half a real night's pauses: " + average);
        assertTrue(show.pauses.stream().allMatch(pause -> pause >= 1.0), "volleys never run together");
        assertTrue(show.pauses.stream().anyMatch(pause -> pause > 5.0), "with real pauses");
    }

    @Test
    void aPreviewShowsItsFirstBurstWithinASecond() {
        SkyEventSchedule.Night preview = SkyEventSchedule.preview(9, PreviewEvent.Event.FIREWORKS, 0.0f, 0.0f);
        int beatTicks = SkyEventSchedule.beatTicks(preview);
        for (long chosen = 1_000; chosen < 200_000; chosen += 997) {
            long delay = SkyEventSchedule.previewDelay(SEED, preview, chosen, 10);
            // The first burst in the show's own time after the moment it was chosen.
            long showChosen = chosen - delay;
            long first = Long.MAX_VALUE;
            for (long beat = Math.floorDiv(showChosen, beatTicks) - 1; beat <= Math.floorDiv(showChosen, beatTicks) + 1; beat++) {
                for (int burst = 0; burst < SkyEventSchedule.volleySize(SEED, preview, beat); burst++) {
                    long tick = SkyEventSchedule.burstTick(SEED, preview, beat, burst);
                    if (tick >= showChosen) {
                        first = Math.min(first, tick);
                    }
                }
            }
            assertTrue(first - showChosen <= 10, "first burst " + (first - showChosen) + " ticks after choosing");
        }
    }

    /** A show's pauses between volleys and its gaps within volleys, in seconds, and how many volleys were single bursts. */
    private record Show(List<Double> pauses, List<Double> followers, int volleys, int singles) {
    }

    private static Show show(SkyEventSchedule.Night night, int beats) {
        List<Double> pauses = new ArrayList<>();
        List<Double> followers = new ArrayList<>();
        int singles = 0;
        long lastBurst = Long.MIN_VALUE;
        for (long beat = 0; beat < beats; beat++) {
            int bursts = SkyEventSchedule.volleySize(SEED, night, beat);
            singles += bursts == 1 ? 1 : 0;
            for (int burst = 0; burst < bursts; burst++) {
                long tick = SkyEventSchedule.burstTick(SEED, night, beat, burst);
                if (lastBurst != Long.MIN_VALUE) {
                    (burst == 0 ? pauses : followers).add((tick - lastBurst) / 20.0);
                }
                lastBurst = tick;
            }
        }
        return new Show(pauses, followers, beats, singles);
    }

    @Test
    void aPreviewShowsOnlyItsEventTowardTheView() {
        float bearing = 2.0f;
        SkyEventSchedule.Night aurora = SkyEventSchedule.preview(4, PreviewEvent.Event.AURORA, bearing, 0.1f);
        assertTrue(aurora.auroraStrength() > 0.8f && !aurora.meteorShower() && !aurora.fireworks());
        assertEquals(bearing, aurora.auroraBearing());

        SkyEventSchedule.Night meteors = SkyEventSchedule.preview(4, PreviewEvent.Event.METEOR_SHOWER, bearing, 0.1f);
        assertTrue(meteors.meteorShower() && meteors.auroraStrength() == 0.0f && !meteors.fireworks());

        SkyEventSchedule.Night fireworks = SkyEventSchedule.preview(4, PreviewEvent.Event.FIREWORKS, bearing, 0.5f);
        assertTrue(fireworks.fireworks() && !fireworks.meteorShower() && fireworks.auroraStrength() == 0.0f);
        assertEquals(bearing, fireworks.fireworksAzimuth());
        assertTrue(fireworks.fireworksLift() > 0.0f, "looking up lifts the bursts into view");

        SkyEventSchedule.Night stars = SkyEventSchedule.preview(4, PreviewEvent.Event.FALLING_STARS, bearing, 0.1f);
        assertTrue(!stars.meteorShower() && !stars.fireworks() && stars.auroraStrength() == 0.0f,
            "falling stars are the sky pass's, not a sky event");
    }

    @Test
    void meteorShowerNightsAreTheSameAsIn097() {
        // 0.9.8 draws a shower's meteors in the sky shader, but the nights that have one are
        // still those 0.9.7 picked, from the same salt of the night's hash.
        int showers = 0;
        for (long night = 0; night < 2000; night++) {
            long hash = SkyEventSchedule.mix(SEED, night);
            boolean expected = SkyEventSchedule.unit(SkyEventSchedule.mix(hash, 1)) < SkyEventSchedule.METEOR_SHOWER_CHANCE;
            assertEquals(expected, SkyEventSchedule.night(SEED, night).meteorShower());
            showers += expected ? 1 : 0;
        }
        assertTrue(showers > 300 && showers < 500);
    }

    @Test
    void windIsTheSameForTheSameDayAndChangesBetweenDays() {
        float[] a = new float[2];
        float[] b = new float[2];
        long dayFourNoon = 4 * SkyEventSchedule.DAY_TICKS + 6_000;
        Wind.sample(SEED, dayFourNoon, 100_000, 0.0f, a);
        Wind.sample(SEED, dayFourNoon, 100_000, 0.0f, b);
        assertEquals(a[0], b[0]);
        assertEquals(a[1], b[1]);
        Wind.sample(SEED, dayFourNoon + SkyEventSchedule.DAY_TICKS, 100_000, 0.0f, b);
        assertNotEquals(a[0], b[0]);
        float calm = (float) Math.hypot(a[0], a[1]);
        Wind.sample(SEED, dayFourNoon, 100_000, 1.0f, b);
        assertTrue(Math.hypot(b[0], b[1]) > calm * 3, "storms blow harder");
    }

    @Test
    void windTurnsSmoothlyIntoTheNextDay() {
        // Clock and game time advance together, tick by tick, across midnight and the turn, for
        // many days and game-time offsets.
        for (int day = 1; day < 60; day++) {
            long start = day * SkyEventSchedule.DAY_TICKS - 200;
            long gameStart = 300_000L + day * 7_919L;
            double previous = Wind.heading(SEED, start, gameStart);
            for (long tick = 1; tick < 1_600; tick++) {
                double heading = Wind.heading(SEED, start + tick, gameStart + tick);
                double step = Math.abs(Math.IEEEremainder(heading - previous, Math.PI * 2.0));
                assertTrue(step < 0.01, "heading jumped by " + step + " on day " + day + " at tick " + tick);
                previous = heading;
            }
        }
    }
}
