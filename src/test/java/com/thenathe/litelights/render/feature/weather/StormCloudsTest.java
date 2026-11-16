package com.thenathe.litelights.render.feature.weather;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cloud particles per storm type (storms17): on keeps every puff and the random sequence untouched. */
class StormCloudsTest {
    @Test
    void allCloudsOnDrawsNoRandomNumber() {
        RandomSource random = RandomSource.create(17L);
        RandomSource untouched = RandomSource.create(17L);
        for (int i = 0; i < 100; i++) {
            assertTrue(WeatherParticles.keeps(1.0f, random));
        }
        assertEquals(untouched.nextLong(), random.nextLong(), "the spawns that follow are the same as before the setting");
    }

    @Test
    void offKeepsNoneAndAShareKeepsAboutThatShare() {
        RandomSource random = RandomSource.create(3L);
        for (int i = 0; i < 100; i++) {
            assertFalse(WeatherParticles.keeps(0.0f, random));
        }
        int kept = 0;
        for (int i = 0; i < 10_000; i++) {
            kept += WeatherParticles.keeps(0.25f, random) ? 1 : 0;
        }
        assertEquals(2500, kept, 150);
    }
}
