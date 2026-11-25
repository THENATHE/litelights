package com.thenathe.litelights.render.feature.water.splash;

/**
 * How big a jump splash is (idea by Chai, Particular): everything scales with how fast the player or
 * mob hit the water and how wide it is. Pure arithmetic, unit-tested; {@link SplashEffects} spawns
 * the particles.
 *
 * <p>Speeds are vertical speeds in blocks per tick. For reference, a fall of 1 block arrives at
 * about 0.4, 3 blocks at 0.6, 10 blocks at 1.2 and 25 blocks at 1.9.
 */
public record SplashPlan(float energy, float widthScale, int droplets, int columnDroplets, float dropletSpeed,
                         float ringStart, float ringEnd, int ringLifetime, int ripples, float rippleEnd, int rippleLifetime) {
    /** Slower than this is wading in, not a splash. */
    public static final double MINIMUM_SPEED = 0.15;
    private static final int MOST_DROPLETS = 90;

    /** Returns null when the entry is too gentle to splash or particles are off. */
    public static SplashPlan of(double verticalSpeed, float entityWidth, float particleDensity) {
        double speed = Math.abs(verticalSpeed);
        if (speed < MINIMUM_SPEED || particleDensity <= 0.0f) {
            return null;
        }
        float energy = (float) Math.clamp((speed - 0.08) / 0.5, 0.15, 4.0);
        float width = Math.clamp(entityWidth / 0.6f, 0.4f, 3.0f);
        float root = (float) Math.sqrt(energy);
        int droplets = Math.min(MOST_DROPLETS, Math.round((6 + 14 * energy) * (float) Math.sqrt(width) * particleDensity));
        int column = energy > 2.0f ? Math.round((4 + 3 * (energy - 2.0f)) * particleDensity) : 0;
        int ripples = 1 + (energy > 0.8f ? 1 : 0) + (energy > 2.0f ? 1 : 0);
        return new SplashPlan(energy, width, droplets, column, 0.12f + 0.11f * root,
            0.35f * width, (0.9f + 0.35f * energy) * width, Math.round(14 + 3 * energy),
            ripples, (1.4f + 0.5f * energy) * width, Math.round(30 + 6 * energy));
    }

    /** Whether a tracked entity just went into water hard enough to splash. */
    public static boolean entered(boolean wasInWater, boolean inWater, double verticalSpeed) {
        return inWater && !wasInWater && verticalSpeed <= -MINIMUM_SPEED;
    }
}
