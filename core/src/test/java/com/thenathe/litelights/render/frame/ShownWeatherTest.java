package com.thenathe.litelights.render.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.thenathe.litelights.render.feature.clouds.DynamicClouds;
import org.junit.jupiter.api.Test;

/**
 * With any cloud choice but the Dynamic clouds, nothing paces the weather: the world's rain and
 * thunder a seasons mod gives are passed through exactly as they are, as in 1.0.9.
 */
class ShownWeatherTest {
    @Test
    void withoutTheDynamicCloudsTheWorldsLevelsAreTheRealOnes() {
        assertFalse(DynamicClouds.get().pacesWeather(null), "nothing paces the weather before the Dynamic clouds tick in a level");
        for (float real : new float[] {0.0f, 0.37f, 1.0f}) {
            assertEquals(real, ShownWeather.worldRain(null, real));
            assertEquals(real, ShownWeather.worldThunder(null, real));
        }
    }
}
