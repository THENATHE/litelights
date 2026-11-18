package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettings.DistanceFill;
import com.thenathe.litelights.render.RenderSettings.ParticleDensity;
import com.thenathe.litelights.render.RenderSettings.SpawnDistance;
import com.thenathe.litelights.render.RenderSettings.WeatherDensity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleReachTest {
    private static RenderSettings.Weather weather(WeatherDensity density, SpawnDistance distance, DistanceFill fill) {
        RenderSettings.Weather d = RenderSettings.Weather.DEFAULTS;
        return new RenderSettings.Weather(d.precipitation(), density, d.rainMist(), d.driftingSand(), d.sandstorms(),
            d.stormAtmosphere(), d.polarLights(), d.meteorNights(), d.farFireworks(), distance, fill, d.rainDrips(), d.wallStreaks(),
            d.glass(), d.storms(), d.mistStyle(), d.puddles(), d.rainbows(), d.snowyLeaves());
    }

    private static RenderSettings.Particles particles(SpawnDistance distance, DistanceFill fill) {
        RenderSettings.Particles d = RenderSettings.Particles.DEFAULTS;
        return new RenderSettings.Particles(d.density(), d.snowSandSprites(), d.fireflySwarms(), d.dustSpecks(), d.pollen(),
            d.spores(), d.enhancedFire(), d.emberVeins(), d.burnMarks(), d.enhancedWater(), distance, fill,
            d.fallingLeaves());
    }

    @Test
    void theDefaultsAreTodaysNearDistance() {
        assertEquals(SpawnDistance.NEAR, RenderSettings.Weather.DEFAULTS.distance());
        assertEquals(DistanceFill.SPREAD, RenderSettings.Weather.DEFAULTS.distanceFill());
        assertEquals(SpawnDistance.NEAR, RenderSettings.Particles.DEFAULTS.distance());
        assertEquals(1.0f, SpawnDistance.NEAR.radius);
        assertEquals(ParticleReach.NEAR_GRID_HALF, ParticleReach.gridHalf(SpawnDistance.NEAR));
        // At Near both choices are the same.
        assertEquals(1.0f, ParticleReach.countScale(SpawnDistance.NEAR, DistanceFill.FILL));
        assertEquals(1.0f, ParticleReach.countScale(SpawnDistance.NEAR, DistanceFill.SPREAD));
    }

    @Test
    void eachStepReachesFurther() {
        SpawnDistance[] steps = SpawnDistance.values();
        for (int i = 1; i < steps.length; i++) {
            assertTrue(steps[i].radius > steps[i - 1].radius, steps[i] + " reaches further than " + steps[i - 1]);
            assertTrue(ParticleReach.gridHalf(steps[i]) >= ParticleReach.gridHalf(steps[i - 1]));
        }
    }

    @Test
    void spreadKeepsTheCountAndFillKeepsTheDensity() {
        for (SpawnDistance distance : SpawnDistance.values()) {
            assertEquals(1.0f, ParticleReach.countScale(distance, DistanceFill.SPREAD), distance + " with Spread");
            float area = distance.radius * distance.radius;
            assertEquals(area, ParticleReach.countScale(distance, DistanceFill.FILL), 1.0e-6f, distance + " with Fill");
        }
    }

    @Test
    void capsScaleUnderFillOnly() {
        float[] near = ParticleBudget.unscaled();
        float[] spread = ParticleBudget.unscaled();
        float[] fill = ParticleBudget.unscaled();
        ParticleReach.capScales(weather(WeatherDensity.MEDIUM, SpawnDistance.NEAR, DistanceFill.SPREAD),
            particles(SpawnDistance.NEAR, DistanceFill.SPREAD), near);
        ParticleReach.capScales(weather(WeatherDensity.MEDIUM, SpawnDistance.VERY_FAR, DistanceFill.SPREAD),
            particles(SpawnDistance.VERY_FAR, DistanceFill.SPREAD), spread);
        ParticleReach.capScales(weather(WeatherDensity.MEDIUM, SpawnDistance.VERY_FAR, DistanceFill.FILL),
            particles(SpawnDistance.VERY_FAR, DistanceFill.FILL), fill);
        // Near: weather's share is the coverage and its headroom times the density; the rest unscaled.
        assertEquals(ParticleBudget.WEATHER_COVERAGE * ParticleBudget.WEATHER_HEADROOM * WeatherDensity.MEDIUM.particles,
            near[ParticleCategory.WEATHER.ordinal()], 1.0e-5f);
        assertEquals(1.0f, near[ParticleCategory.AMBIENT.ordinal()]);
        for (ParticleCategory category : ParticleCategory.values()) {
            assertEquals(near[category.ordinal()], spread[category.ordinal()], 1.0e-6f, category + " keeps its cap with Spread");
        }
        float area = SpawnDistance.VERY_FAR.radius * SpawnDistance.VERY_FAR.radius;
        assertEquals(near[ParticleCategory.WEATHER.ordinal()] * area, fill[ParticleCategory.WEATHER.ordinal()], 1.0e-4f);
        assertEquals(area, fill[ParticleCategory.AMBIENT.ordinal()], 1.0e-6f);
        assertEquals(area, fill[ParticleCategory.NETHER.ordinal()], 1.0e-6f);
        assertEquals(area, fill[ParticleCategory.END.ordinal()], 1.0e-6f);
        assertEquals(1.0f, fill[ParticleCategory.FIRE.ordinal()], "fire stays tied to its source");
        int nearTotal = ParticleBudget.totalCap(ParticleDensity.FULL, near);
        int fillTotal = ParticleBudget.totalCap(ParticleDensity.FULL, fill);
        assertTrue(fillTotal > nearTotal * 4, "the total cap grows with the filled area");
    }

    @Test
    void theTwoDistancesAreIndependent() {
        float[] scales = ParticleBudget.unscaled();
        float[] ranges = ParticleBudget.unscaled();
        RenderSettings.Weather farWeather = weather(WeatherDensity.LIGHT, SpawnDistance.FAR, DistanceFill.FILL);
        RenderSettings.Particles nearParticles = particles(SpawnDistance.NEAR, DistanceFill.FILL);
        ParticleReach.capScales(farWeather, nearParticles, scales);
        ParticleReach.rangeScales(farWeather, nearParticles, ranges);
        assertEquals(1.0f, scales[ParticleCategory.AMBIENT.ordinal()]);
        assertEquals(1.0f, ranges[ParticleCategory.AMBIENT.ordinal()]);
        assertEquals(SpawnDistance.FAR.radius, ranges[ParticleCategory.WEATHER.ordinal()]);

        RenderSettings.Weather nearWeather = weather(WeatherDensity.LIGHT, SpawnDistance.NEAR, DistanceFill.SPREAD);
        RenderSettings.Particles farParticles = particles(SpawnDistance.MEDIUM, DistanceFill.SPREAD);
        ParticleReach.rangeScales(nearWeather, farParticles, ranges);
        assertEquals(1.0f, ranges[ParticleCategory.WEATHER.ordinal()]);
        assertEquals(SpawnDistance.MEDIUM.radius, ranges[ParticleCategory.AMBIENT.ordinal()]);
        assertEquals(SpawnDistance.MEDIUM.radius, ranges[ParticleCategory.NETHER.ordinal()]);
        assertEquals(SpawnDistance.MEDIUM.radius, ranges[ParticleCategory.END.ordinal()]);
        assertEquals(1.0f, ranges[ParticleCategory.FIRE.ordinal()]);
    }

    @Test
    void splashesAndHazeKeepTheirRangeAndWeatherReachesFurther() {
        float[] ranges = ParticleBudget.unscaled();
        ParticleReach.rangeScales(weather(WeatherDensity.MEDIUM, SpawnDistance.VERY_FAR, DistanceFill.SPREAD),
            particles(SpawnDistance.VERY_FAR, DistanceFill.SPREAD), ranges);
        assertEquals(ParticleKind.RAIN_SPLASH.range, ParticleReach.range(ParticleKind.RAIN_SPLASH, ranges));
        assertEquals(ParticleKind.RAIN_RIPPLE.range, ParticleReach.range(ParticleKind.RAIN_RIPPLE, ranges));
        assertEquals(ParticleKind.SMOKE.range, ParticleReach.range(ParticleKind.SMOKE, ranges));
        assertEquals(ParticleKind.MIST.range, ParticleReach.range(ParticleKind.MIST, ranges), "mist is the air close by");
        assertEquals(ParticleKind.SAND_CLOUD.range, ParticleReach.range(ParticleKind.SAND_CLOUD, ranges));
        assertEquals(ParticleKind.END_MIST.range, ParticleReach.range(ParticleKind.END_MIST, ranges));
        assertEquals(ParticleKind.RAIN.range * SpawnDistance.VERY_FAR.radius, ParticleReach.range(ParticleKind.RAIN, ranges), 1.0e-4f);
        assertEquals(ParticleKind.FIREFLY.range * SpawnDistance.VERY_FAR.radius, ParticleReach.range(ParticleKind.FIREFLY, ranges), 1.0e-4f);
    }

    @Test
    void theGridCoversTheRainCircleUpwindAndSnapsToItsStep() {
        for (SpawnDistance distance : SpawnDistance.values()) {
            int half = ParticleReach.gridHalf(distance);
            assertEquals(0, half % ParticleReach.GRID_SNAP, distance + " snaps");
            // Rain spawns within 22 blocks times the reach, shifted up to about 8 blocks upwind.
            assertTrue(half >= 22.0f * distance.radius + 8.0f, distance + " covers the rain circle");
        }
    }

    @Test
    void particlesBeyondTheirScaledRangeAreRemoved() {
        ParticleState from = new ParticleState(8);
        ParticleState to = new ParticleState(8);
        // A firefly 60 blocks out: past Near's 40 (and the margin), inside Very far's 96.
        from.append(ParticleKind.FIREFLY, 60, 70, 0, 0, 0, 0, 0.1f, 200, -1, 0, 1);
        Simulation.Inputs near = new Simulation.Inputs();
        near.cameraY = 70;
        near.cap = 8;
        SpawnQueue queue = new SpawnQueue();
        queue.begin(ParticleDensity.FULL, new int[ParticleCategory.values().length], 0);
        Simulation.step(from, to, queue, FlatGround.INSTANCE, near);
        assertEquals(0, to.count, "removed at Near");

        Simulation.Inputs veryFar = new Simulation.Inputs();
        veryFar.cameraY = 70;
        veryFar.cap = 8;
        ParticleReach.rangeScales(RenderSettings.Weather.DEFAULTS, particles(SpawnDistance.VERY_FAR, DistanceFill.SPREAD), veryFar.rangeScales);
        Simulation.step(from, to, queue, FlatGround.INSTANCE, veryFar);
        assertEquals(1, to.count, "kept at Very far");
    }

    @Test
    void aResizedStateKeepsItsParticlesAndCounts() {
        ParticleState state = new ParticleState(8);
        state.append(ParticleKind.RAIN, 1, 2, 3, 0, -1, 0, 0.03f, 60, -1, 0, 1);
        state.append(ParticleKind.FIREFLY, 4, 5, 6, 0, 0, 0, 0.1f, 200, -1, 0, 2);
        state.append(ParticleKind.SMOKE, 7, 8, 9, 0, 0, 0, 0.3f, 100, -1, 0, 3);
        ParticleState larger = state.resized(64);
        assertEquals(64, larger.capacity);
        assertEquals(3, larger.count);
        assertEquals(5.0, larger.y[1]);
        assertEquals(1, larger.categoryCounts[ParticleCategory.AMBIENT.ordinal()]);
        ParticleState smaller = state.resized(2);
        assertEquals(2, smaller.count);
        assertEquals(0, smaller.categoryCounts[ParticleCategory.FIRE.ordinal()], "what didn't fit isn't counted");
        assertEquals(1, smaller.categoryCounts[ParticleCategory.WEATHER.ordinal()]);
    }

    /** Flat ground far below, so nothing lands. */
    private enum FlatGround implements Simulation.Ground {
        INSTANCE;

        @Override
        public double groundHeight(double x, double z) {
            return 0.0;
        }

        @Override
        public boolean isWaterSurface(double x, double z) {
            return false;
        }
    }
}
