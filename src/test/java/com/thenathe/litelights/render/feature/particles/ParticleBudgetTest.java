package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.RenderSettings.ParticleDensity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleBudgetTest {
    @Test
    void amountSetsTheHardCap() {
        assertEquals(ParticleBudget.FULL_CAP, ParticleBudget.totalCap(ParticleDensity.FULL));
        assertEquals(Math.round(ParticleBudget.FULL_CAP * 0.4f), ParticleBudget.totalCap(ParticleDensity.SPARSE));
        assertEquals(0, ParticleBudget.totalCap(ParticleDensity.OFF));
    }

    @Test
    void noneSpawnsNothingAnywhere() {
        for (ParticleCategory category : ParticleCategory.values()) {
            assertEquals(0, ParticleBudget.room(ParticleDensity.OFF, category, 0, 0, 0, 0));
        }
        assertEquals(0.0f, ParticleBudget.spawnScale(ParticleDensity.OFF));
    }

    @Test
    void reducedThinsSpawnRates() {
        assertEquals(1.0f, ParticleBudget.spawnScale(ParticleDensity.FULL));
        assertTrue(ParticleBudget.spawnScale(ParticleDensity.SPARSE) < 0.5f);
    }

    @Test
    void aCategoryCannotTakeMoreThanItsShare() {
        int weatherCap = ParticleBudget.categoryCap(ParticleDensity.FULL, ParticleCategory.WEATHER);
        assertTrue(weatherCap < ParticleBudget.FULL_CAP);
        assertEquals(0, ParticleBudget.room(ParticleDensity.FULL, ParticleCategory.WEATHER, weatherCap, 0, weatherCap, 0));
        // Ambient particles still have room while the weather is full.
        assertTrue(ParticleBudget.room(ParticleDensity.FULL, ParticleCategory.AMBIENT, 0, 0, weatherCap, 0) > 0);
    }

    @Test
    void theTotalCapHoldsAcrossCategories() {
        int cap = ParticleBudget.FULL_CAP;
        assertEquals(5, ParticleBudget.room(ParticleDensity.FULL, ParticleCategory.AMBIENT, 0, 0, cap - 10, 5));
        assertEquals(0, ParticleBudget.room(ParticleDensity.FULL, ParticleCategory.AMBIENT, 0, 0, cap, 0));
        assertEquals(0, ParticleBudget.room(ParticleDensity.FULL, ParticleCategory.AMBIENT, 0, 0, cap + 50, 0));
    }

    @Test
    void queuedSpawnsCountAgainstTheCap() {
        int ambientCap = ParticleBudget.categoryCap(ParticleDensity.FULL, ParticleCategory.AMBIENT);
        assertEquals(ambientCap - 30, ParticleBudget.room(ParticleDensity.FULL, ParticleCategory.AMBIENT, 20, 10, 20, 10));
    }

    @Test
    void fractionalRatesSpawnNowAndThen() {
        assertEquals(0, ParticleBudget.countFromRate(0.0f, 0.0f));
        assertEquals(1, ParticleBudget.countFromRate(0.25f, 0.1f));
        assertEquals(0, ParticleBudget.countFromRate(0.25f, 0.3f));
        assertEquals(3, ParticleBudget.countFromRate(2.5f, 0.4f));
        assertEquals(2, ParticleBudget.countFromRate(2.5f, 0.6f));
    }

    @Test
    void spawnQueueRefusesPastTheCap() {
        SpawnQueue queue = new SpawnQueue();
        int[] alive = new int[ParticleCategory.values().length];
        int fireCap = ParticleBudget.categoryCap(ParticleDensity.FULL, ParticleCategory.FIRE);
        alive[ParticleCategory.FIRE.ordinal()] = fireCap - 2;
        queue.begin(ParticleDensity.FULL, alive, fireCap - 2);
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            if (queue.add(ParticleKind.EMBER, 0, 0, 0, 0, 0, 0, 0.1f, 20, -1, 0, i)) {
                accepted++;
            }
        }
        assertEquals(2, accepted);
        assertEquals(0, queue.room(ParticleCategory.FIRE));
    }

    @Test
    void weatherRoomGrowsWithTheWeatherScaleAndNothingElseDoes() {
        float light = ParticleBudget.WEATHER_COVERAGE;
        float dense = ParticleBudget.WEATHER_COVERAGE * 4.5f;
        float[] lightScales = weatherScaled(light);
        float[] denseScales = weatherScaled(dense);
        int weatherLight = ParticleBudget.categoryCap(ParticleDensity.FULL, ParticleCategory.WEATHER, lightScales);
        int weatherDense = ParticleBudget.categoryCap(ParticleDensity.FULL, ParticleCategory.WEATHER, denseScales);
        assertEquals(Math.round(ParticleBudget.FULL_CAP * ParticleCategory.WEATHER.share * light), weatherLight);
        assertTrue(weatherDense > weatherLight * 4, "Dense has room for four and a half times the weather");
        assertEquals(ParticleBudget.categoryCap(ParticleDensity.FULL, ParticleCategory.FIRE),
            ParticleBudget.categoryCap(ParticleDensity.FULL, ParticleCategory.FIRE, denseScales));
        // The total grows by exactly the weather's extra room, so the other shares still fit.
        int extraWeather = weatherDense - ParticleBudget.categoryCap(ParticleDensity.FULL, ParticleCategory.WEATHER);
        assertEquals(ParticleBudget.FULL_CAP + extraWeather, ParticleBudget.totalCap(ParticleDensity.FULL, denseScales), 1);
        // Sparse still takes its share of every cap; Off still allows nothing.
        assertEquals(Math.round(weatherDense * 0.4f), ParticleBudget.categoryCap(ParticleDensity.SPARSE, ParticleCategory.WEATHER, denseScales), 3);
        assertEquals(0, ParticleBudget.totalCap(ParticleDensity.OFF, denseScales));
    }

    @Test
    void everyScaledCategoryAddsItsExtraRoomToTheTotal() {
        float[] scales = ParticleBudget.unscaled();
        scales[ParticleCategory.WEATHER.ordinal()] = 6.0f;
        scales[ParticleCategory.AMBIENT.ordinal()] = 3.24f;
        int expected = ParticleBudget.FULL_CAP;
        for (ParticleCategory category : ParticleCategory.values()) {
            expected += ParticleBudget.categoryCap(ParticleDensity.FULL, category, scales)
                - ParticleBudget.categoryCap(ParticleDensity.FULL, category);
        }
        assertEquals(expected, ParticleBudget.totalCap(ParticleDensity.FULL, scales), 2);
        // A scale below 1 never shrinks the total under the base cap.
        scales = ParticleBudget.unscaled();
        scales[ParticleCategory.FIRE.ordinal()] = 0.5f;
        assertEquals(ParticleBudget.FULL_CAP, ParticleBudget.totalCap(ParticleDensity.FULL, scales));
    }

    @Test
    void firefliesFollowTheAmountLessSteeply() {
        assertEquals(1.0f, ParticleBudget.fireflySpawnScale(ParticleDensity.FULL));
        assertEquals(0.0f, ParticleBudget.fireflySpawnScale(ParticleDensity.OFF));
        float sparse = ParticleBudget.fireflySpawnScale(ParticleDensity.SPARSE);
        assertTrue(sparse > ParticleBudget.spawnScale(ParticleDensity.SPARSE), "Sparse keeps more of the fireflies than of the rest");
        assertTrue(sparse < 1.0f);
    }

    @Test
    void theQueueUsesTheCategoryScales() {
        SpawnQueue queue = new SpawnQueue();
        int[] alive = new int[ParticleCategory.values().length];
        float[] scales = weatherScaled(ParticleBudget.WEATHER_COVERAGE * 2.0f);
        scales[ParticleCategory.AMBIENT.ordinal()] = 1.96f;
        queue.begin(ParticleDensity.FULL, scales, alive, 0);
        for (ParticleCategory category : ParticleCategory.values()) {
            assertEquals(ParticleBudget.categoryCap(ParticleDensity.FULL, category, scales), queue.categoryCap(category));
        }
        // Changing the caller's array afterwards doesn't change this tick's caps.
        scales[ParticleCategory.AMBIENT.ordinal()] = 1.0f;
        assertTrue(queue.categoryCap(ParticleCategory.AMBIENT) > ParticleBudget.categoryCap(ParticleDensity.FULL, ParticleCategory.AMBIENT));
    }

    @Test
    void theQueueGrowsAndShrinksButNeverBelowItsBase() {
        SpawnQueue queue = new SpawnQueue();
        assertEquals(SpawnQueue.BASE_CAPACITY, queue.capacity());
        queue.ensureCapacity(SpawnQueue.BASE_CAPACITY * 5);
        assertEquals(SpawnQueue.BASE_CAPACITY * 5, queue.capacity());
        int[] alive = new int[ParticleCategory.values().length];
        float[] scales = weatherScaled(1000.0f);
        queue.begin(ParticleDensity.FULL, scales, alive, 0);
        int accepted = 0;
        while (queue.add(ParticleKind.RAIN, 0, 0, 0, 0, 0, 0, 0.02f, 20, -1, 0, accepted)) {
            accepted++;
        }
        assertEquals(SpawnQueue.BASE_CAPACITY * 5, accepted, "the queue fills to its new capacity");
        queue.ensureCapacity(10);
        assertEquals(SpawnQueue.BASE_CAPACITY, queue.capacity());
    }

    private static float[] weatherScaled(float weatherScale) {
        float[] scales = ParticleBudget.unscaled();
        scales[ParticleCategory.WEATHER.ordinal()] = weatherScale;
        return scales;
    }
}
