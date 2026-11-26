package com.thenathe.litelights.render.feature.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Under Almanac's regional weather the rain reaches the higher of Almanac's storm top and the
 * drawn cloud's top, so raising the clouds leaves no dry band under them (weather-ceiling-117).
 */
class WeatherCeilingTest {
    private static final float NONE_OVERHEAD = Float.NEGATIVE_INFINITY;
    private static final float NOT_DRAWN = Float.NaN;

    @Test
    void raisedCloudsCarryTheRainUpToThem() {
        // A rain storm topped at 220 by Almanac, drawn from Cloud height 400 with its deck to 1,000.
        float ceiling = WeatherCeiling.of(220.0f, 1_000.0f);
        assertEquals(1_000.0f, ceiling);
        for (double y : new double[] {100.0, 192.0, 221.0, 300.0, 399.0, 420.0, 999.0}) {
            assertEquals(0.8f, WeatherCeiling.target(0.8f, y, ceiling), "rain at y " + y + ", between Almanac's top and the cloud");
        }
        assertEquals(0.0f, WeatherCeiling.target(0.8f, 1_001.0, ceiling), "nothing over the drawn cloud");
    }

    @Test
    void lowerCloudsTakeNoRainAway() {
        // Fancy clouds lowered to 128 (4.5 blocks tall) under a storm Almanac tops at 240.
        float ceiling = WeatherCeiling.of(240.0f, 132.5f);
        assertEquals(240.0f, ceiling);
        assertEquals(0.6f, WeatherCeiling.target(0.6f, 200.0, ceiling));
        assertEquals(0.0f, WeatherCeiling.target(0.6f, 241.0, ceiling));
    }

    @Test
    void withoutADrawnCloudAlmanacsTopStands() {
        assertEquals(230.0f, WeatherCeiling.of(230.0f, NONE_OVERHEAD), "Dynamic clouds with no rain cloud near the camera");
        assertEquals(230.0f, WeatherCeiling.of(230.0f, NOT_DRAWN), "no clouds drawn");
        assertEquals(0.0f, WeatherCeiling.target(1.0f, 260.0, WeatherCeiling.of(230.0f, NONE_OVERHEAD)));
    }

    @Test
    void withoutAStormTheCameraIsAskedAsItIs() {
        assertEquals(500.0f, WeatherCeiling.of(Float.NaN, 500.0f));
        assertTrue(Float.isNaN(WeatherCeiling.of(Float.NaN, NOT_DRAWN)));
        assertEquals(0.3f, WeatherCeiling.target(0.3f, 2_000.0, Float.NaN), "nothing limits the height");
        assertEquals(812.0, WeatherCeiling.askedHeight(812.0, Float.NaN));
    }

    @Test
    void overTheStormAlmanacIsAskedJustUnderItsTop() {
        assertEquals(150.0, WeatherCeiling.askedHeight(150.0, 220.0f), "under the top: the camera's own height");
        assertEquals(219.0, WeatherCeiling.askedHeight(300.0, 220.0f));
        assertEquals(319.0, WeatherCeiling.askedHeight(330.0, 320.0f));
    }

    @Test
    void levelsMoveAtVanillasPace() {
        assertEquals(0.81f, WeatherCeiling.ease(0.8f, 1.0f), 1.0e-6f);
        assertEquals(0.79f, WeatherCeiling.ease(0.8f, 0.0f), 1.0e-6f);
        assertEquals(0.505f, WeatherCeiling.ease(0.5f, 0.505f), 1.0e-6f);
    }
}
