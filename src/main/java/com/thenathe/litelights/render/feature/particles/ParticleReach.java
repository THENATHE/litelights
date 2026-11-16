package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettings.DistanceFill;
import com.thenathe.litelights.render.RenderSettings.SpawnDistance;

/**
 * How far particles reach around the camera, from the Weather distance (rain, snow, mist, blowing
 * sand) and the Particle distance (fireflies, dust, pollen, spores, the Nether's and the End's air).
 * Fire, splashes and falling water stay tied to their source and never reach further.
 *
 * <p>Each distance multiplies its spawners' areas and its kinds' draw ranges by the distance's
 * radius. With Spread that is all: the same count covers the larger area. With Fill the spawn
 * rates and the category's share of the cap grow with the area too, so the density stays Near's
 * all the way out. Fireflies never grow with Fill: they keep their count at every distance. The
 * haze kinds (mist, dust clouds, ash haze, End mist) keep their Near area, count and range: they
 * are the air close by, and more of them further out stacked up into a white-out.
 * Pure arithmetic, so the rules can be tested on their own.
 */
public final class ParticleReach {
    /** The column grid's half-width at Near, in blocks: rain's 22-block circle plus room upwind. */
    static final int NEAR_GRID_HALF = 32;
    /** The grid recentres in steps of this many blocks, so its half-width is kept a multiple of it. */
    static final int GRID_SNAP = 8;

    private ParticleReach() {
    }

    /** How much more a spawner spawns at this distance: the area's growth with Fill, otherwise 1. */
    public static float countScale(SpawnDistance distance, DistanceFill fill) {
        return fill == DistanceFill.FILL ? distance.radius * distance.radius : 1.0f;
    }

    /** Each category's share of the cap is multiplied by this (by ordinal; see {@link ParticleBudget}). */
    public static void capScales(RenderSettings.Weather weather, RenderSettings.Particles particles, float[] into) {
        float ambient = countScale(particles.distance(), particles.distanceFill());
        into[ParticleCategory.WEATHER.ordinal()] = ParticleBudget.WEATHER_COVERAGE * weather.density().particles
            * countScale(weather.distance(), weather.distanceFill());
        into[ParticleCategory.AMBIENT.ordinal()] = ambient;
        into[ParticleCategory.FIRE.ordinal()] = 1.0f;
        into[ParticleCategory.NETHER.ordinal()] = ambient;
        into[ParticleCategory.END.ordinal()] = ambient;
    }

    /** Each category's draw and removal range is multiplied by this (by ordinal). */
    public static void rangeScales(RenderSettings.Weather weather, RenderSettings.Particles particles, float[] into) {
        float ambient = particles.distance().radius;
        into[ParticleCategory.WEATHER.ordinal()] = weather.distance().radius;
        into[ParticleCategory.AMBIENT.ordinal()] = ambient;
        into[ParticleCategory.FIRE.ordinal()] = 1.0f;
        into[ParticleCategory.NETHER.ordinal()] = ambient;
        into[ParticleCategory.END.ordinal()] = ambient;
    }

    /** A kind's range with these scales: kinds tied to their source (splashes) and the haze kinds keep theirs. */
    public static float range(ParticleKind kind, float[] rangeScales) {
        return kind.fixedRange ? kind.range : kind.range * rangeScales[kind.category.ordinal()];
    }

    /**
     * Half the width of the column grid the weather needs at this distance: Near's 32 blocks
     * times the radius, rounded up to the grid's snap.
     */
    public static int gridHalf(SpawnDistance weatherDistance) {
        int blocks = (int) Math.ceil(NEAR_GRID_HALF * weatherDistance.radius);
        return (blocks + GRID_SNAP - 1) / GRID_SNAP * GRID_SNAP;
    }
}
