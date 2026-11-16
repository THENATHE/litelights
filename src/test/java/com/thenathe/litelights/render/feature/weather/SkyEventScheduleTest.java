package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.config.PreviewEvent;
import org.junit.jupiter.api.Test;
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
    void meteorsAndBurstsAreFixedBySlot() {
        SkyEventSchedule.Night night = SkyEventSchedule.night(SEED, 7);
        float[] first = new float[6];
        float[] second = new float[6];
        int meteors = 0;
        for (long slot = 0; slot < 1000; slot++) {
            boolean found = SkyEventSchedule.meteor(SEED, night, slot, first);
            assertEquals(found, SkyEventSchedule.meteor(SEED, night, slot, second));
            if (found) {
                meteors++;
                for (int i = 0; i < 6; i++) {
                    assertEquals(first[i], second[i]);
                }
                assertTrue(first[1] >= Math.toRadians(12.0) - 1e-6, "meteors start above the horizon");
            }
        }
        assertEquals(SkyEventSchedule.METEOR_CHANCE_PER_SLOT * 1000, meteors, 60);
    }

    @Test
    void fireworksStayLowOverTheHorizonAndInTheEvening() {
        SkyEventSchedule.Night night = SkyEventSchedule.night(SEED, 3);
        float[] burst = new float[5];
        for (long slot = 0; slot < 500; slot++) {
            if (SkyEventSchedule.firework(SEED, night, slot, burst)) {
                assertTrue(burst[1] > Math.toRadians(6.0) && burst[1] < Math.toRadians(22.0));
                assertTrue(burst[3] >= 0 && burst[3] < 6);
            }
        }
        assertTrue(SkyEventSchedule.fireworksHour(13_500));
        assertTrue(SkyEventSchedule.fireworksHour(3 * 24000 + 15_000));
        assertTrue(!SkyEventSchedule.fireworksHour(6_000));
        assertTrue(!SkyEventSchedule.fireworksHour(20_000));
    }

    @Test
    void meteorsAreBrighterAndSomeAreFireballs() {
        SkyEventSchedule.Night night = SkyEventSchedule.night(SEED, 11);
        float[] meteor = new float[6];
        int found = 0;
        int fireballs = 0;
        for (long slot = 0; slot < 4000; slot++) {
            if (SkyEventSchedule.meteor(SEED, night, slot, meteor)) {
                found++;
                fireballs += meteor[5] > 0.5f ? 1 : 0;
                assertTrue(meteor[4] >= 0.75f && meteor[4] <= 1.0f);
                assertTrue(meteor[2] >= Math.toRadians(14.0) - 1e-6 && meteor[2] <= Math.toRadians(34.0) + 1e-6);
            }
        }
        assertEquals(SkyEventSchedule.FIREBALL_CHANCE, fireballs / (double) found, 0.03);
    }

    @Test
    void aPreviewShowsOnlyItsEventTowardTheView() {
        float bearing = 2.0f;
        SkyEventSchedule.Night aurora = SkyEventSchedule.preview(4, PreviewEvent.Event.AURORA, bearing, 0.1f);
        assertTrue(aurora.auroraStrength() > 0.8f && !aurora.meteorShower() && !aurora.fireworks());
        assertEquals(bearing, aurora.auroraBearing());

        SkyEventSchedule.Night meteors = SkyEventSchedule.preview(4, PreviewEvent.Event.METEOR_SHOWER, bearing, 0.1f);
        assertTrue(meteors.meteorShower() && meteors.auroraStrength() == 0.0f && !meteors.fireworks());
        assertEquals(bearing, meteors.radiantAzimuth());
        assertTrue(meteors.radiantElevation() >= Math.toRadians(25.0) - 1e-6 && meteors.radiantElevation() <= Math.toRadians(60.0) + 1e-6);

        SkyEventSchedule.Night fireworks = SkyEventSchedule.preview(4, PreviewEvent.Event.FIREWORKS, bearing, 0.5f);
        assertTrue(fireworks.fireworks() && !fireworks.meteorShower() && fireworks.auroraStrength() == 0.0f);
        assertEquals(bearing, fireworks.fireworksAzimuth());
        assertTrue(fireworks.fireworksLift() > 0.0f, "looking up lifts the bursts into view");

        SkyEventSchedule.Night stars = SkyEventSchedule.preview(4, PreviewEvent.Event.FALLING_STARS, bearing, 0.1f);
        assertTrue(!stars.meteorShower() && !stars.fireworks() && stars.auroraStrength() == 0.0f,
            "falling stars are the sky pass's, not a sky event");
    }

    @Test
    void aPreviewShowsSomethingWithinAboutASecond() {
        // How long a preview waits for its first meteor and burst, whatever the slot it starts in.
        SkyEventSchedule.Night meteors = SkyEventSchedule.preview(9, PreviewEvent.Event.METEOR_SHOWER, 0.0f, 0.0f);
        SkyEventSchedule.Night fireworks = SkyEventSchedule.preview(9, PreviewEvent.Event.FIREWORKS, 0.0f, 0.0f);
        float[] out = new float[6];
        long meteorTicks = 0;
        long burstTicks = 0;
        int starts = 0;
        for (long start = 0; start < 5000; start += 7) {
            long meteorWait = 0;
            while (!SkyEventSchedule.meteor(SEED, meteors, start + meteorWait, out)) {
                meteorWait++;
            }
            long burstWait = 0;
            while (!SkyEventSchedule.firework(SEED, fireworks, start + burstWait, out)) {
                burstWait++;
            }
            meteorTicks += meteorWait * SkyEventSchedule.METEOR_SLOT_TICKS;
            burstTicks += burstWait * SkyEventSchedule.FIREWORK_SLOT_TICKS;
            starts++;
        }
        assertTrue(meteorTicks / (double) starts < 20.0, "first meteor after " + meteorTicks / (double) starts + " ticks on average");
        assertTrue(burstTicks / (double) starts < 20.0, "first burst after " + burstTicks / (double) starts + " ticks on average");
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
