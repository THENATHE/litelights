package com.thenathe.litelights.render.feature.weather;

/**
 * What the weather leaves on surfaces around the camera, eased over time: how much rain is falling
 * now, how wet things still are, frost, settled snow and sand. Weather on glass draws the frost,
 * snow and sand; the rain drips follow the wetness, so they taper off as things dry, and the wall
 * streaks the falling rain.
 *
 * <p>The rules (seconds of game time; nothing changes while the game is paused):
 * <ul>
 *   <li><b>Falling</b> follows the rain within about a second and a half, and lets go over about
 *   three, so the wall streaks thin out instead of all stopping at once.</li>
 *   <li><b>Wetness</b> rises to the falling rain within a few seconds and, once the rain stops,
 *   dries at a steady rate: from soaked to dry in {@link #DRYING_SECONDS}.</li>
 *   <li><b>Frost</b> grows where it is cold enough to snow, over {@link #FROST_GROW_SECONDS}, and
 *   clears in a quarter of a minute where it isn't.</li>
 *   <li><b>Snow</b> settles while it snows, over a minute and a half; it stays a long time where it
 *   is still cold and melts in twenty seconds where it isn't.</li>
 *   <li><b>Dust</b> settles during a sandstorm, stays for a long while on sandy land afterwards,
 *   blows away in twenty seconds elsewhere, and rain washes it off.</li>
 * </ul>
 * Plain arithmetic, no game objects, so it is unit-tested on its own.
 */
public final class SurfaceWeather {
    static final float WETTING_SECONDS = 2.0f;
    static final float DRYING_SECONDS = 30.0f;
    static final float FALL_RISE_SECONDS = 1.5f;
    static final float FALL_LET_GO_SECONDS = 3.0f;
    static final float FROST_GROW_SECONDS = 40.0f;
    static final float FROST_CLEAR_SECONDS = 15.0f;
    static final float SNOW_SETTLE_SECONDS = 90.0f;
    static final float SNOW_KEEP_SECONDS = 600.0f;
    static final float SNOW_MELT_SECONDS = 20.0f;
    static final float DUST_SETTLE_SECONDS = 25.0f;
    static final float DUST_KEEP_SECONDS = 900.0f;
    static final float DUST_BLOW_SECONDS = 20.0f;
    static final float DUST_WASH_SECONDS = 5.0f;

    /** What the weather is doing at the camera this moment. */
    public static final class Conditions {
        /** Rain falling, 0..1 (0 where it snows or nothing falls). */
        public float rain;
        /** Snow falling, 0..1. */
        public float snow;
        /** Cold enough to snow here (biome and height). */
        public boolean cold;
        /** The sandstorm as LiteLights draws it, 0..1. */
        public float sandstorm;
        /** Sandy land (deserts, badlands, savannas), where settled sand stays. */
        public boolean sandy;
        /** The sand here is red (badlands). */
        public boolean redSand;

        public void clear() {
            rain = 0.0f;
            snow = 0.0f;
            cold = false;
            sandstorm = 0.0f;
            sandy = false;
            redSand = false;
        }
    }

    private float falling;
    private float wetness;
    private float frost;
    private float snow;
    private float dust;
    private boolean redSand;

    /** Moves everything on by {@code seconds} under these conditions. */
    public void update(float seconds, Conditions now) {
        if (seconds <= 0.0f) {
            return;
        }
        falling = ease(falling, now.rain, now.rain > falling ? FALL_RISE_SECONDS : FALL_LET_GO_SECONDS, seconds);
        if (now.rain > wetness) {
            wetness = ease(wetness, now.rain, WETTING_SECONDS, seconds);
        } else {
            wetness = Math.max(now.rain, wetness - seconds / DRYING_SECONDS);
        }

        frost = now.cold ? Math.min(1.0f, frost + seconds / FROST_GROW_SECONDS) : Math.max(0.0f, frost - seconds / FROST_CLEAR_SECONDS);

        if (now.snow > 0.0f) {
            snow = Math.min(1.0f, snow + seconds * now.snow / SNOW_SETTLE_SECONDS);
        } else {
            snow = Math.max(0.0f, snow - seconds / (now.cold ? SNOW_KEEP_SECONDS : SNOW_MELT_SECONDS));
        }

        if (now.sandstorm > 0.05f) {
            dust = Math.min(1.0f, dust + seconds * now.sandstorm / DUST_SETTLE_SECONDS);
            redSand = now.redSand;
        } else {
            dust = Math.max(0.0f, dust - seconds / (now.sandy ? DUST_KEEP_SECONDS : DUST_BLOW_SECONDS));
        }
        dust = Math.max(0.0f, dust - seconds * wetness / DUST_WASH_SECONDS);
    }

    /** Forgets everything, as on entering another world or dimension. */
    public void reset() {
        falling = 0.0f;
        wetness = 0.0f;
        frost = 0.0f;
        snow = 0.0f;
        dust = 0.0f;
        redSand = false;
    }

    public float falling() {
        return falling;
    }

    public float wetness() {
        return wetness;
    }

    public float frost() {
        return frost;
    }

    public float snow() {
        return snow;
    }

    public float dust() {
        return dust;
    }

    public boolean redSand() {
        return redSand;
    }

    /** Exponential approach toward {@code target} with the given time constant. */
    private static float ease(float value, float target, float timeConstant, float seconds) {
        float step = 1.0f - (float) Math.exp(-seconds / timeConstant);
        return value + (target - value) * step;
    }
}
