package com.thenathe.litelights.render.feature.weather;

/**
 * The wind that carries LiteLights' rain, snow, sand, smoke and grit, and that grass, plants and
 * leaves sway in. Its direction and strength come from the world seed and the day, so every player
 * on a world sees the same wind on the same day; within a day it swings slowly and gusts, and over
 * the first minute of a day it turns from yesterday's heading to today's. Storms make it stronger.
 *
 * <p>Pure functions of their inputs, so it can be tested and computed on any thread.
 */
public final class Wind {
    /** How long the wind takes to turn to the new day's heading, in ticks. */
    private static final long TURN_TICKS = 1200L;

    private Wind() {
    }

    /**
     * The wind in blocks per tick, written to {@code out[0]} (x) and {@code out[1]} (z).
     *
     * @param seed      the world's hashed seed
     * @param clockTime the overworld clock, in ticks (its whole days pick the day's wind)
     * @param gameTime  ticks since the world began, with the partial tick if wanted
     * @param storm     extra strength from storms, 0 (calm) to 1 (the strongest blizzard or sandstorm)
     */
    public static void sample(long seed, long clockTime, double gameTime, float storm, float[] out) {
        long dayHash = dayHash(seed, Math.floorDiv(clockTime, SkyEventSchedule.DAY_TICKS));
        double phase = phase(dayHash);
        double angle = heading(seed, clockTime, gameTime);
        double calm = 0.025 + 0.035 * SkyEventSchedule.unit(SkyEventSchedule.mix(dayHash, 19));
        double gust = 1.0 + 0.35 * Math.sin(gameTime * 0.013 + phase) * Math.sin(gameTime * 0.0071 + phase * 0.5);
        double speed = (calm + storm * 0.3) * gust;
        out[0] = (float) (Math.cos(angle) * speed);
        out[1] = (float) (Math.sin(angle) * speed);
    }

    /**
     * How much the storms add to the wind, 0 (calm) to 1, for {@link #sample}'s {@code storm}: rain
     * brings some wind of its own, more in thunder, and sandstorms and blizzards blow hardest. The
     * one rule for everything that moves with the wind: the particles, the sea's waves and the
     * swinging lanterns.
     *
     * @param sandstorm the sandstorm's strength where the camera is (StormAtmosphere), 0 to 1
     * @param blizzard  the blizzard's strength where the camera is, 0 to 1
     */
    public static float stormStrength(float rain, float thunder, float sandstorm, float blizzard) {
        float rainWind = rain * (0.55f + 0.45f * thunder) * 0.45f;
        return Math.max(rainWind, Math.max(sandstorm, blizzard));
    }

    /**
     * The direction the wind blows toward, in radians: x is its cosine, z its sine. It changes
     * smoothly, also from one day to the next, so swaying plants never snap round.
     */
    public static double heading(long seed, long clockTime, double gameTime) {
        long day = Math.floorDiv(clockTime, SkyEventSchedule.DAY_TICKS);
        long todayHash = dayHash(seed, day);
        double today = dayHeading(todayHash, gameTime);
        long intoDay = Math.floorMod(clockTime, SkyEventSchedule.DAY_TICKS);
        if (intoDay >= TURN_TICKS) {
            return today;
        }
        long yesterdayHash = dayHash(seed, day - 1);
        double yesterday = dayHeading(yesterdayHash, gameTime);
        // Turn the short way round, decided once for the day from the two days' base headings, so
        // the slow swing of both can't flip the direction halfway through the turn.
        double baseTurn = baseHeading(todayHash) - baseHeading(yesterdayHash);
        double fullTurns = Math.rint(baseTurn / (Math.PI * 2.0));
        double turn = today - yesterday - fullTurns * Math.PI * 2.0;
        double t = (double) intoDay / TURN_TICKS;
        return yesterday + turn * t * t * (3.0 - 2.0 * t);
    }

    private static double dayHeading(long dayHash, double gameTime) {
        // The direction swings by up to about 30 degrees either way over a few hours.
        return baseHeading(dayHash) + 0.5 * Math.sin(gameTime / SkyEventSchedule.DAY_TICKS * Math.PI * 2.0 * 3.0 + phase(dayHash));
    }

    private static double baseHeading(long dayHash) {
        return SkyEventSchedule.unit(dayHash) * Math.PI * 2.0;
    }

    private static long dayHash(long seed, long day) {
        return SkyEventSchedule.mix(seed ^ 0x5DEECE66DL, day);
    }

    private static double phase(long dayHash) {
        return SkyEventSchedule.unit(SkyEventSchedule.mix(dayHash, 7)) * Math.PI * 2.0;
    }
}
