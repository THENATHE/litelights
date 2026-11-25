package com.thenathe.litelights.render.feature.water.splash;

/**
 * How big an Advanced jump splash is: a square ring spreading from the landing point, water
 * thrown up from it, and droplets, all growing with how fast the player or mob hit the water and
 * how wide it is. Pure arithmetic, unit-tested; {@link SplashEffects} spawns the particles.
 *
 * <p>Speeds are vertical speeds in blocks per tick, as in {@link SplashPlan}: a fall of 1 block
 * arrives at about 0.4, 3 blocks at 0.6, 10 blocks at 1.2 and 25 blocks at 1.9.
 *
 * @param ringReach      how far the square ring spreads, in blocks from the landing point to its sides
 * @param ringLifetime   ticks the ring takes to spread and fade
 * @param secondRing     whether a fainter second ring follows (harder landings)
 * @param crownPeak      the tallest frame of the rising water, 0 to {@link #CROWN_FRAMES} - 1
 * @param crownRiseTicks ticks the water takes to rise to its peak
 * @param crownFallTicks ticks it takes to fall back
 */
public record AdvancedSplashPlan(float energy, float ringReach, int ringLifetime, boolean secondRing, int crownPeak,
                                 int crownRiseTicks, int crownFallTicks, int droplets, float dropletSpeed) {
    /** Frames of the rising water's sprite (WaterParticleSprites.Set.CROWN). */
    public static final int CROWN_FRAMES = 12;
    /** The furthest the square ring's sprite reaches, in blocks (its last frame). */
    public static final float RING_REACH_LIMIT = 41.0f / 16.0f;
    private static final int MOST_DROPLETS = 60;

    /** Returns null when the entry is too gentle to splash or particles are off. */
    public static AdvancedSplashPlan of(double verticalSpeed, float entityWidth, float particleDensity) {
        double speed = Math.abs(verticalSpeed);
        if (speed < SplashPlan.MINIMUM_SPEED || particleDensity <= 0.0f) {
            return null;
        }
        float energy = (float) Math.clamp((speed - 0.08) / 0.5, 0.15, 4.0);
        float size = (float) Math.sqrt(Math.clamp(entityWidth / 0.6f, 0.4f, 3.0f));
        float reach = Math.clamp((0.7f + 0.55f * energy) * size, 0.6f, RING_REACH_LIMIT);
        int crownPeak = Math.clamp(Math.round((1.0f + 2.6f * energy) * size), 1, CROWN_FRAMES - 1);
        int droplets = Math.min(MOST_DROPLETS, Math.round((4 + 10 * energy) * size * particleDensity));
        return new AdvancedSplashPlan(energy, reach, Math.round(14 + 5 * energy), energy > 1.0f, crownPeak,
            3 + crownPeak / 3, 6 + crownPeak / 2, droplets, 0.16f + 0.12f * (float) Math.sqrt(energy));
    }
}
