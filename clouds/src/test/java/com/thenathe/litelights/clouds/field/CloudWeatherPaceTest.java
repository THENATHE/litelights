package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The weather's pace (user, 2026-11-25): the clouds close in 30 to 40 seconds, the rain shown waits
 * for them and eases in and out, and where the weather can be seen coming the clouds start early.
 */
class CloudWeatherPaceTest {
    /** Vanilla's own rain ramp: 0.01 a tick. */
    private static float vanillaRamp(int tick, int start) {
        return Math.clamp((tick - start) * 0.01f, 0.0f, 1.0f);
    }

    @Test
    void withNoForecastTheFirstDropsFallUnderADarkeningSky() {
        CloudWeather weather = new CloudWeather();
        weather.tick(0.0f, 0.0f);
        int firstDrop = -1;
        int fullRain = -1;
        for (int tick = 1; tick <= 1_200; tick++) {
            weather.tick(vanillaRamp(tick, 0), 0.0f);
            if (firstDrop < 0 && weather.shownRain(1.0f) > 0.0f) {
                firstDrop = tick;
                assertTrue(weather.rain() >= CloudWeather.RAIN_FROM, "the deck is under way when the first drops fall");
            }
            if (fullRain < 0 && weather.shownRain(1.0f) >= 0.99f) {
                fullRain = tick;
            }
        }
        System.out.println("weather pace, no forecast: first drops at " + firstDrop + " ticks, full rain at " + fullRain);
        assertTrue(firstDrop >= 200 && firstDrop <= 400, "first drops after " + firstDrop + " ticks");
        assertTrue(fullRain <= 1_000, "full rain only after " + fullRain + " ticks");
    }

    @Test
    void aForecastClosesTheDeckBeforeTheRainStarts() {
        CloudWeather weather = new CloudWeather();
        weather.tick(0.0f, 0.0f);
        int rainStarts = 1_000;
        for (int tick = 1; tick < rainStarts; tick++) {
            float due = CloudWeather.dueness(rainStarts - tick, CloudWeather.RAIN_LEAD_TICKS);
            weather.tick(0.0f, 0.0f, due, 0.0f);
            assertEquals(0.0f, weather.shownRain(1.0f), "no rain is shown before the server's");
        }
        assertTrue(weather.rain() >= 0.9f, "the deck is closed as the rain begins: " + weather.rain());
        int fullRain = -1;
        for (int tick = rainStarts; tick < rainStarts + 600; tick++) {
            weather.tick(vanillaRamp(tick, rainStarts), 0.0f, 1.0f, 0.0f);
            if (fullRain < 0 && weather.shownRain(1.0f) >= 0.99f) {
                fullRain = tick - rainStarts;
            }
        }
        System.out.println("weather pace, forecast: deck closed when the rain began, full rain " + fullRain + " ticks after");
        assertTrue(fullRain >= 0 && fullRain <= 250, "with the clouds ready the rain eases in within seconds: " + fullRain);
    }

    @Test
    void theRainFadesOutWhileTheCloudsLinger() {
        CloudWeather weather = new CloudWeather();
        weather.tick(1.0f, 0.0f);
        int dry = -1;
        for (int tick = 1; tick <= 2_000; tick++) {
            weather.tick(1.0f - vanillaRamp(tick, 0), 0.0f);
            if (dry < 0 && weather.shownRain(1.0f) == 0.0f) {
                dry = tick;
                assertTrue(weather.rain() > 0.7f, "the deck is still there when the last drops fall");
            }
        }
        assertTrue(dry > 100 && dry <= 400, "the rain eased out over " + dry + " ticks");
    }

    @Test
    void thunderNeedsItsTowersAndNeverOutrunsTheRain() {
        CloudWeather weather = new CloudWeather();
        weather.tick(1.0f, 0.0f);
        int firstThunder = -1;
        for (int tick = 1; tick <= 4_000; tick++) {
            weather.tick(1.0f, vanillaRamp(tick, 0), 0.0f, 0.0f);
            assertTrue(weather.shownThunder(1.0f) <= weather.shownRain(1.0f) + 1.0e-6f);
            if (firstThunder < 0 && weather.shownThunder(1.0f) > 0.0f) {
                firstThunder = tick;
                assertTrue(weather.thunder() >= CloudWeather.THUNDER_FROM, "the towers are building when the thunder shows");
            }
        }
        assertTrue(firstThunder > 0 && firstThunder <= 800, "thunder first showed at " + firstThunder);
    }

    @Test
    void partialTicksBlendTheShownLevels() {
        CloudWeather weather = new CloudWeather();
        weather.tick(1.0f, 0.0f);
        weather.tick(0.0f, 0.0f);
        float before = weather.shownRain(0.0f);
        float after = weather.shownRain(1.0f);
        assertTrue(after < before);
        assertEquals((before + after) * 0.5f, weather.shownRain(0.5f), 1.0e-6f);
    }

    @Test
    void theWorldsRainRisesAtThePaceOfTheRainShown() {
        CloudWeather weather = new CloudWeather();
        weather.tick(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        for (int tick = 1; tick <= 1_200; tick++) {
            float rain = vanillaRamp(tick, 0);
            // A desert out of season: the player's share is the whole level, so the two must match.
            weather.tick(rain, 0.0f, rain, 0.0f, 0.0f, 0.0f);
            assertEquals(weather.shownRain(1.0f), weather.shownWorldRain(1.0f), 1.0e-6f, "tick " + tick);
            if (weather.shownRain(1.0f) == 0.0f) {
                assertEquals(0.0f, weather.shownWorldRain(1.0f), "no world rain before the first drops, tick " + tick);
            }
        }
        assertEquals(1.0f, weather.shownWorldRain(1.0f), 1.0e-6f);
    }

    @Test
    void theWorldsRainAndThunderFallWithTheRainShown() {
        CloudWeather weather = new CloudWeather();
        weather.tick(1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f);
        int worldDry = -1;
        for (int tick = 1; tick <= 2_000; tick++) {
            float rain = 1.0f - vanillaRamp(tick, 0);
            weather.tick(rain, rain, rain, rain, 0.0f, 0.0f);
            assertTrue(weather.shownWorldThunder(1.0f) <= weather.shownWorldRain(1.0f) + 1.0e-6f);
            if (worldDry < 0 && weather.shownWorldRain(1.0f) == 0.0f) {
                worldDry = tick;
            }
        }
        assertTrue(worldDry > 100 && worldDry <= 400, "the world's rain eased out over " + worldDry + " ticks, not vanilla's 100");
    }

    @Test
    void theWorldsThunderWaitsForItsTowers() {
        CloudWeather weather = new CloudWeather();
        weather.tick(1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f);
        for (int tick = 1; tick <= 4_000; tick++) {
            float thunder = vanillaRamp(tick, 0);
            weather.tick(1.0f, thunder, 1.0f, thunder, 0.0f, 0.0f);
            assertEquals(weather.shownThunder(1.0f), weather.shownWorldThunder(1.0f), 1.0e-6f, "tick " + tick);
            if (weather.thunder() < CloudWeather.THUNDER_FROM) {
                assertEquals(0.0f, weather.shownWorldThunder(1.0f), "no haboob before the towers, tick " + tick);
            }
        }
    }

    @Test
    void withoutASeasonsModTheWorldsLevelsAreThePlayers() {
        CloudWeather shared = new CloudWeather();
        CloudWeather plain = new CloudWeather();
        shared.tick(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        plain.tick(0.0f, 0.0f, 0.0f, 0.0f);
        for (int tick = 1; tick <= 3_000; tick++) {
            float rain = tick < 1_500 ? vanillaRamp(tick, 0) : 1.0f - vanillaRamp(tick, 1_500);
            float thunder = rain * vanillaRamp(tick, 200);
            float due = CloudWeather.dueness(Math.max(0, 300 - tick), CloudWeather.RAIN_LEAD_TICKS);
            plain.tick(rain, thunder, due, 0.0f);
            assertEquals(plain.shownRain(1.0f), plain.shownWorldRain(1.0f), "tick " + tick);
            assertEquals(plain.shownThunder(1.0f), plain.shownWorldThunder(1.0f), "tick " + tick);
            shared.tick(rain, thunder, rain, thunder, due, 0.0f);
            assertEquals(plain.shownRain(1.0f), shared.shownRain(1.0f), "tick " + tick);
            assertEquals(plain.rain(), shared.rain(), "tick " + tick);
        }
    }

    @Test
    void whereThePlayersShareIsNothingTheWorldsRainStillComesAtThePace() {
        CloudWeather weather = new CloudWeather();
        weather.tick(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        int worldFirst = -1;
        for (int tick = 1; tick <= 1_200; tick++) {
            // The rain event doesn't reach the player's climate: their share stays 0, the world's rains.
            weather.tick(0.0f, 0.0f, vanillaRamp(tick, 0), 0.0f, 0.0f, 0.0f);
            assertEquals(0.0f, weather.shownRain(1.0f), "nothing shown where the player stands");
            assertEquals(0.0f, weather.rain(), "no deck over the player");
            if (worldFirst < 0 && weather.shownWorldRain(1.0f) > 0.0f) {
                worldFirst = tick;
            }
        }
        assertTrue(worldFirst >= 200 && worldFirst <= 400, "the world's rain came in after " + worldFirst + " ticks, as the rain shown would");
        assertEquals(1.0f, weather.shownWorldRain(1.0f), 1.0e-6f);
    }

    @Test
    void dueness() {
        assertEquals(0.0f, CloudWeather.dueness(2_000, 800));
        assertEquals(0.5f, CloudWeather.dueness(400, 800), 1.0e-6f);
        assertEquals(1.0f, CloudWeather.dueness(0, 800));
    }

    @Test
    void aBoltBeforeTheTowersHurriesThem() {
        CloudWeather calm = new CloudWeather();
        CloudWeather struck = new CloudWeather();
        calm.tick(1.0f, 0.0f);
        struck.tick(1.0f, 0.0f);
        int calmTicks = 0;
        int struckTicks = 0;
        for (int tick = 0; tick < 4_000; tick++) {
            calm.tick(1.0f, 1.0f);
            struck.tick(1.0f, 1.0f);
            if (tick == 100) {
                struck.boltStruck();
            }
            calmTicks += calm.thunder() < 1.0f ? 1 : 0;
            struckTicks += struck.thunder() < 1.0f ? 1 : 0;
        }
        assertTrue(calmTicks >= 2_300, "unhurried, the towers take two minutes: " + calmTicks);
        assertTrue(struckTicks < 1_100, "once a bolt struck they catch up in under a minute: " + struckTicks);
        // Once caught up, a later storm builds at the usual pace again.
        for (int tick = 0; tick < 6_000; tick++) {
            struck.tick(1.0f, 0.0f);
        }
        int again = 0;
        for (int tick = 0; tick < 4_000; tick++) {
            struck.tick(1.0f, 1.0f);
            again += struck.thunder() < 1.0f ? 1 : 0;
        }
        assertTrue(again >= 2_300, "the hurry ends with the storm it was for: " + again);
    }

    @Test
    void underAlmanacsStormsTheRainShownIsAlmanacsOnceItsCloudIsOverhead() {
        CloudWeather weather = new CloudWeather();
        // The storm's cloud is already overhead (it reaches past its rain): the rain shows as Almanac eases it in.
        for (int tick = 0; tick <= 120; tick++) {
            float rain = vanillaRamp(tick, 20);
            weather.tickUnder(rain, 0.0f, rain, 0.0f, 1.0f);
            assertEquals(rain, weather.shownRain(1.0f), 1.0e-6f, "at tick " + tick);
        }
        // Nothing falls where Almanac says nothing falls, however much cloud there is.
        weather.tickUnder(0.0f, 0.0f, 0.0f, 0.0f, 1.0f);
        assertEquals(0.0f, weather.shownRain(1.0f));
        assertEquals(1.0f, weather.rain(), "the clouds' level is the cloud over the camera");
    }

    @Test
    void underAStormStillClosingInTheRainWaitsForItsCloud() {
        CloudWeather weather = new CloudWeather();
        weather.tickUnder(1.0f, 1.0f, 1.0f, 1.0f, 0.2f);
        assertEquals(0.0f, weather.shownRain(1.0f), "no rain from a sky with hardly any cloud in it");
        weather.tickUnder(1.0f, 1.0f, 1.0f, 1.0f, 0.65f);
        float half = weather.shownRain(1.0f);
        assertTrue(half > 0.2f && half < 0.8f, "rain under a half-closed cloud: " + half);
        assertTrue(weather.shownThunder(1.0f) <= half, "thunder never above the rain");
        weather.tickUnder(1.0f, 1.0f, 1.0f, 1.0f, 0.95f);
        assertEquals(1.0f, weather.shownRain(1.0f));
        assertEquals(1.0f, weather.shownWorldRain(1.0f));
    }
}
