package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * No cloud base goes below the sky's base height in any weather (the user, 2026-11-25: "base cloud
 * limit should be 192 minimum"), in a dimension with another cloud height too, while the rain
 * deck still sits lowest.
 */
class CloudFloorTest {
    @Test
    void nothingReachesBelowTheFloorInAnyWeather() {
        for (float base : new float[] {192.0f, 128.0f}) {
            for (float[] weather : new float[][] {{0.0f, 0.0f}, {0.5f, 0.0f}, {1.0f, 0.0f}, {1.0f, 1.0f}}) {
                for (double tick : new double[] {30_000.0, 61_000.0, 150_000.0}) {
                    CloudField field = new CloudField(VanillaWeatherSky.sky(TestSkies.SEED, base, tick, weather[0], weather[1], 0.0, 0.0, 8_192.0));
                    CloudColumn column = new CloudColumn();
                    float lowest = Float.POSITIVE_INFINITY;
                    for (int z = -40; z <= 40; z++) {
                        for (int x = -40; x <= 40; x++) {
                            field.sampleWorld(tick, x * 97.0, z * 97.0, 8.0, column);
                            if (!column.isClear()) {
                                lowest = Math.min(lowest, column.lowestBottom());
                            }
                        }
                    }
                    assertTrue(lowest >= base, "a cloud reached down to " + lowest + " over a floor of " + base);
                }
            }
        }
    }

    @Test
    void theRainDeckSitsLowJustAboveTheFloor() {
        CloudField field = TestSkies.vanilla(TestSkies.TICK, 1.0f, 0.0f);
        CloudColumn column = new CloudColumn();
        double sum = 0.0;
        int count = 0;
        for (int z = -30; z <= 30; z++) {
            for (int x = -30; x <= 30; x++) {
                field.sampleWorld(TestSkies.TICK, x * 131.0, z * 131.0, 8.0, column);
                if (!column.isClear()) {
                    sum += column.lowestBottom() - TestSkies.BASE;
                    count++;
                }
            }
        }
        double meanBase = sum / count;
        System.out.println("rain deck: mean base " + meanBase + " blocks above the floor");
        assertTrue(meanBase < 30.0, "the rain deck's base should hang low: " + meanBase);
    }
}
