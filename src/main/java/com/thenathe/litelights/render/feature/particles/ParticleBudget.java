package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.RenderSettings.ParticleDensity;
import java.util.Arrays;

/**
 * The particle cap. Full allows {@link #FULL_CAP} live particles in all, Sparse a share of that
 * and of every spawn rate, Off nothing. Each category may also use only its own share of the cap.
 * Pure arithmetic, so the rules can be tested on their own.
 *
 * <p>Why 3072: at that count the render thread's share (culling, interpolation and building the
 * quads) stays under about 0.4 ms per frame, which leaves room in the 0.6 ms budget for the tick
 * work and the sky events.
 *
 * <p>A category's share can be multiplied by a scale ({@link ParticleReach#capScales}). Weather's
 * always is: {@link #WEATHER_COVERAGE} times the weather density's particle multiplier, because
 * rain, snow and blowing sand fill the whole circle around the camera, so turning never shows thin
 * air; most of them are behind the camera at any moment and cost only the simulation (on the
 * worker) and a cull. Any category's share also grows with the area when its distance is set to
 * Fill. The total grows by exactly the scaled categories' extra room, so the others still fit.
 */
public final class ParticleBudget {
    public static final int FULL_CAP = 3072;
    static final float SPARSE_SHARE = 0.4f;
    /**
     * How much more weather is alive when it fills the whole circle around the camera instead of
     * mostly the view: rain used to spawn 80 % into a cone of a third of the circle, so the same
     * drops in view need about 2.7 times as many in all.
     */
    public static final float WEATHER_COVERAGE = 2.75f;
    /**
     * Fireflies' spawn rate at Sparse. Fireflies follow the amount less steeply than everything
     * else (Sparse 40 %), so Full doesn't swarm and Sparse still shows a few.
     */
    static final float FIREFLY_SPARSE_SHARE = 0.65f;

    private static final float[] UNSCALED = unscaled();

    private ParticleBudget() {
    }

    /** One scale per category, all 1: every share as it is. */
    public static float[] unscaled() {
        float[] scales = new float[ParticleCategory.values().length];
        Arrays.fill(scales, 1.0f);
        return scales;
    }

    /** The most particles that may be alive at once, with every category at its base share. */
    public static int totalCap(ParticleDensity density) {
        return totalCap(density, UNSCALED);
    }

    /** The most particles that may be alive at once, with each category's share multiplied by its scale (by ordinal). */
    public static int totalCap(ParticleDensity density, float[] scales) {
        float extra = 0.0f;
        for (ParticleCategory category : ParticleCategory.values()) {
            extra += category.share * Math.max(0.0f, scales[category.ordinal()] - 1.0f);
        }
        return Math.round(baseCap(density) * (1.0f + extra));
    }

    private static float baseCap(ParticleDensity density) {
        return switch (density) {
            case FULL -> FULL_CAP;
            case SPARSE -> FULL_CAP * SPARSE_SHARE;
            case OFF -> 0.0f;
        };
    }

    /** Spawn rates are multiplied by this, so Sparse thins every effect evenly rather than cutting some off. */
    public static float spawnScale(ParticleDensity density) {
        return switch (density) {
            case FULL -> 1.0f;
            case SPARSE -> SPARSE_SHARE;
            case OFF -> 0.0f;
        };
    }

    /** Fireflies' spawn rates are multiplied by this instead of {@link #spawnScale}. */
    public static float fireflySpawnScale(ParticleDensity density) {
        return switch (density) {
            case FULL -> 1.0f;
            case SPARSE -> FIREFLY_SPARSE_SHARE;
            case OFF -> 0.0f;
        };
    }

    public static int categoryCap(ParticleDensity density, ParticleCategory category) {
        return categoryCap(density, category, UNSCALED);
    }

    public static int categoryCap(ParticleDensity density, ParticleCategory category, float[] scales) {
        return Math.round(baseCap(density) * category.share * scales[category.ordinal()]);
    }

    /**
     * How many more particles of a category may be spawned now, given what is alive and already
     * queued this tick. Never negative.
     */
    public static int room(ParticleDensity density, ParticleCategory category,
                           int aliveInCategory, int queuedInCategory, int aliveTotal, int queuedTotal) {
        return room(density, UNSCALED, category, aliveInCategory, queuedInCategory, aliveTotal, queuedTotal);
    }

    public static int room(ParticleDensity density, float[] scales, ParticleCategory category,
                           int aliveInCategory, int queuedInCategory, int aliveTotal, int queuedTotal) {
        int categoryRoom = categoryCap(density, category, scales) - aliveInCategory - queuedInCategory;
        int totalRoom = totalCap(density, scales) - aliveTotal - queuedTotal;
        return Math.max(0, Math.min(categoryRoom, totalRoom));
    }

    /**
     * A spawn count for this tick from a rate per tick that may be fractional: the fraction becomes
     * the chance of one more, so low rates still spawn now and then. {@code random} is 0..1.
     */
    public static int countFromRate(float ratePerTick, float random) {
        if (ratePerTick <= 0.0f) {
            return 0;
        }
        int whole = (int) ratePerTick;
        return whole + (random < ratePerTick - whole ? 1 : 0);
    }
}
