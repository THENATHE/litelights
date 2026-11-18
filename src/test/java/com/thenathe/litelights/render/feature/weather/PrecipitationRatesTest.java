package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.feature.particles.ColumnGrid;
import com.thenathe.litelights.render.feature.particles.ParticleKind;
import com.thenathe.litelights.render.feature.particles.SpawnQueue;
import com.thenathe.litelights.render.feature.particles.SpawnQueueAccess;
import com.thenathe.litelights.render.feature.particles.Surroundings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Rain and snow each try at their own rate, and only where the land falls their way: over every
 * column the same drops and flakes come out on average as when snow's attempts ran at rain's rate
 * and most were thrown away (snow's rate a tick over its share of the circle, rain's over its).
 */
class PrecipitationRatesTest {
    private static final int TICKS = 4000;
    /** Full rain, no thunder, no storm, Custom rain and snow at the defaults, Near: the rates' base values. */
    private static final float RAIN_RATE = 56.0f * 2.7f;
    private static final float SNOW_RATE = 15.0f * 1.35f;

    private static Surroundings calmRain() {
        Surroundings s = new Surroundings();
        s.settings = RenderSettings.DEFAULTS;
        s.cameraX = 0.0;
        s.cameraY = 80.0;
        s.cameraZ = 0.0;
        s.rain = 1.0f;
        s.thunder = 0.0f;
        s.overworldSky = true;
        s.weatherAllowed = true;
        return s;
    }

    /** Average drops and flakes queued a tick over land falling as {@code fallAt} says. */
    private static float[] spawnedPerTick(java.util.function.IntBinaryOperator fallAt) {
        Surroundings s = calmRain();
        ColumnGrid grid = new ColumnGrid();
        grid.fillFlatForTests(s.cameraX, s.cameraZ, 64, fallAt);
        SpawnQueue queue = new SpawnQueue();
        long rain = 0;
        long snow = 0;
        for (int tick = 0; tick < TICKS; tick++) {
            SpawnQueueAccess.begin(queue);
            WeatherParticles.precipitation(s, queue, grid, s.settings.weather(), 1.0f);
            rain += SpawnQueueAccess.queued(queue, ParticleKind.RAIN);
            snow += SpawnQueueAccess.queued(queue, ParticleKind.SNOW);
        }
        return new float[] {rain / (float) TICKS, snow / (float) TICKS};
    }

    @Test
    void rainAndSnowEachKeepTheirRateOverTheirOwnLand() {
        float[] rainOnly = spawnedPerTick((x, z) -> ColumnGrid.PRECIPITATION_RAIN);
        assertEquals(RAIN_RATE, rainOnly[0], RAIN_RATE * 0.02f);
        assertEquals(0.0f, rainOnly[1]);
        float[] snowOnly = spawnedPerTick((x, z) -> ColumnGrid.PRECIPITATION_SNOW);
        assertEquals(0.0f, snowOnly[0]);
        assertEquals(SNOW_RATE, snowOnly[1], SNOW_RATE * 0.03f);
    }

    @Test
    void whereBothFallEachTakesItsShareOfTheCircle() {
        // West of x = 0 rains, east snows: half the circle each.
        float[] both = spawnedPerTick((x, z) -> x < 0 ? ColumnGrid.PRECIPITATION_RAIN : ColumnGrid.PRECIPITATION_SNOW);
        assertEquals(RAIN_RATE * 0.5f, both[0], RAIN_RATE * 0.02f);
        assertEquals(SNOW_RATE * 0.5f, both[1], SNOW_RATE * 0.04f);
        float[] dry = spawnedPerTick((x, z) -> ColumnGrid.PRECIPITATION_NONE);
        assertEquals(0.0f, dry[0]);
        assertEquals(0.0f, dry[1]);
    }
}
