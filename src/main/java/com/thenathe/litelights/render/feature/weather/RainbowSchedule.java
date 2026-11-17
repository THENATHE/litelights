package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.render.frame.CelestialLight;

/**
 * When a rainbow follows the rain (Rainbows, idea by AppleNButter, You Must Have The Rain), and how
 * strong it is over its few minutes.
 *
 * <p>Each time rain or a thunderstorm clears by day, there is a chance of a rainbow: two in three
 * after rain and nine in ten after a thunderstorm. Whether this clearing gets one comes from the
 * world's hashed seed and the minute of the world's game time it cleared in, never from a random
 * generator with state, so every player on the world who saw the rain clear sees the same rainbow
 * at the same time. (Two players whose clients see the rain clear on either side of a minute's
 * boundary can disagree; the rain fades over five seconds, so that is rare.)
 *
 * <p>A rainbow sits about 42 degrees from the point opposite the sun, so it shows only while the
 * sun is lower than that: with a high sun the bow is below the horizon, as it really is. The sun
 * moves fast in Minecraft (a quarter of the sky in five minutes), so a rainbow in the morning sinks
 * as it fades and one in the late afternoon rises into view, and about three in five chances fall
 * while the sun is too high for the bow to show. Over all the day's clearings, about a third show
 * one anyone can see ({@code RainbowScheduleTest} measures the share).
 */
public final class RainbowSchedule {
    /** Clearings in the same minute of game time share their chance. */
    static final int SLOT_TICKS = 1200;
    static final double CHANCE_AFTER_RAIN = 0.65;
    static final double CHANCE_AFTER_THUNDER = 0.9;
    /** The bow grows in as the rain clears, holds, and fades: three minutes in all. */
    static final int GROW_TICKS = 400;
    static final int HOLD_TICKS = 2400;
    static final int FADE_TICKS = 800;
    public static final int LIFE_TICKS = GROW_TICKS + HOLD_TICKS + FADE_TICKS;
    /** Rain counts as falling above this level and as cleared below the lower one. */
    static final float RAIN_FALLING = 0.5f;
    static final float RAIN_CLEARED = 0.2f;
    /** A thunderstorm, for the better chance, if thunder rose this far during the rain. */
    static final float THUNDERSTORM = 0.5f;
    private static final long SALT = 0x7261696E626F77L;

    private RainbowSchedule() {
    }

    /** Whether rain that cleared at game tick {@code clearedTick} leaves a rainbow, if the sun is up. */
    public static boolean follows(long seed, long clearedTick, boolean thunderstorm) {
        long slot = Math.floorDiv(clearedTick, SLOT_TICKS);
        double roll = SkyEventSchedule.unit(SkyEventSchedule.mix(seed ^ SALT, slot));
        return roll < (thunderstorm ? CHANCE_AFTER_THUNDER : CHANCE_AFTER_RAIN);
    }

    /** How strong a rainbow is {@code ticks} after the rain cleared, 0 to 1. */
    public static float envelope(double ticks) {
        if (ticks < 0.0 || ticks >= LIFE_TICKS) {
            return 0.0f;
        }
        if (ticks < GROW_TICKS) {
            return smooth((float) (ticks / GROW_TICKS));
        }
        if (ticks < GROW_TICKS + HOLD_TICKS) {
            return 1.0f;
        }
        return 1.0f - smooth((float) ((ticks - GROW_TICKS - HOLD_TICKS) / FADE_TICKS));
    }

    /** The sun's height over the horizon in radians at a time of day (ticks). */
    public static float sunElevation(long clockTime) {
        float angle = CelestialLight.sunAngleForDayTime(Math.floorMod(clockTime, SkyEventSchedule.DAY_TICKS));
        return (float) Math.asin(Math.clamp(Math.cos(angle), -1.0, 1.0));
    }

    /** Whether the sun is up when the rain clears: rainbows are a daytime event. */
    public static boolean daytime(long clockTime) {
        return sunElevation(clockTime) > 0.0f;
    }

    /**
     * How much of a rainbow the sun lets show: none once it has set, all of it from a few degrees
     * up. A higher sun puts the bow lower, which the geometry does by itself.
     */
    public static float sunLight(float sunElevation) {
        return smooth(Math.clamp((sunElevation + 0.01f) / 0.09f, 0.0f, 1.0f));
    }

    private static float smooth(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Follows the rain at the camera tick by tick and notices when it clears: rain that was falling
     * where the camera is (not snow, not a desert's dry spell) and has faded. Plain state, no game
     * objects, so it is unit-tested on its own.
     */
    public static final class Watch {
        private boolean raining;
        private float stormiest;
        private long clearedTick;
        private boolean rainbow;

        /** Forgets the rain, as on entering another world or dimension. */
        public void reset() {
            raining = false;
            stormiest = 0.0f;
            rainbow = false;
        }

        /**
         * One game tick: {@code rain} and {@code thunder} the world's levels, {@code rainsHere}
         * whether the camera's biome has rain (rather than snow or nothing).
         */
        public void tick(long seed, long gameTick, long clockTime, float rain, float thunder, boolean rainsHere) {
            if (rain > RAIN_FALLING && rainsHere) {
                if (!raining) {
                    raining = true;
                    stormiest = 0.0f;
                }
                stormiest = Math.max(stormiest, thunder);
                // Rain again, before the last rainbow faded: it goes with the rain.
                rainbow = false;
                return;
            }
            if (raining && rain < RAIN_CLEARED) {
                raining = false;
                if (rainsHere && daytime(clockTime) && follows(seed, gameTick, stormiest >= THUNDERSTORM)) {
                    rainbow = true;
                    clearedTick = gameTick;
                }
            }
        }

        /** QA only: as if rain had just cleared at {@code gameTick} and left a rainbow. */
        public void startForQa(long gameTick) {
            raining = false;
            rainbow = true;
            clearedTick = gameTick;
        }

        /** The rainbow's strength at this game time, 0 when there is none. */
        public float strength(double gameTime) {
            return rainbow ? envelope(gameTime - clearedTick) : 0.0f;
        }
    }
}
