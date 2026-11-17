package com.thenathe.litelights.render.feature.weather;

/**
 * How far the puddles around the camera have grown, from the rain over time: 0 is dry ground, 1
 * the puddles of a long rain, up to {@link #THUNDER_EXTRA} more in a thunderstorm. The shaders turn
 * it into how much of the low ground holds water, so the puddles grow out from the lowest spots and
 * dry back into them, edges first (include/weather/puddles.glsl).
 *
 * <p>The rules (seconds of game time; nothing changes while the game is paused):
 * <ul>
 *   <li><b>Rain</b> fills them at a steady rate: from dry to full in {@link #FILL_SECONDS} of
 *   steady rain, so they show within the first minute and are full after two and a half.
 *   Lighter rain fills them more slowly; a thunderstorm half again as fast, and further.</li>
 *   <li><b>After the rain</b> they stay as they are for {@link #LINGER_SECONDS}, then dry at a
 *   steady rate, from full to nothing in {@link #DRY_SECONDS}.</li>
 *   <li><b>Where it snows</b> (a cold biome) or nothing falls (a desert), no rain fills them and
 *   what is left goes faster and without waiting, frozen over or soaked up.</li>
 *   <li><b>Joining a world in the rain</b> (rain within its first {@link #JOIN_SECONDS}, as the
 *   server's weather can arrive a moment after the world), they start where that rain would have
 *   left them by now, as it has been falling for a while already.</li>
 * </ul>
 * Plain arithmetic, no game objects, so it is unit-tested on its own.
 */
public final class PuddleWetness {
    static final float FILL_SECONDS = 150.0f;
    static final float LINGER_SECONDS = 60.0f;
    static final float DRY_SECONDS = 240.0f;
    /** How much bigger the puddles of a thunderstorm grow, and how much faster. */
    static final float THUNDER_EXTRA = 0.3f;
    static final float THUNDER_PACE = 0.5f;
    /** Where it snows or never rains, what is left dries this many times faster. */
    static final float COLD_DRYING = 4.0f;
    static final float ARID_DRYING = 2.0f;
    /** Rain lighter than this counts as stopped. */
    static final float RAIN_STOPPED = 0.05f;
    static final float JOIN_SECONDS = 5.0f;

    /** The weather at the camera this moment. */
    public enum Fall { RAIN, SNOW, NONE }

    private float cover;
    private float sinceRain = Float.POSITIVE_INFINITY;
    private float joining = JOIN_SECONDS;

    /**
     * Moves the puddles on by {@code seconds}. {@code fall} is what this biome brings in wet
     * weather; {@code rain} and {@code thunder} the world's levels, 0 to 1.
     */
    public void update(float seconds, Fall fall, float rain, float thunder) {
        boolean raining = fall == Fall.RAIN && rain >= RAIN_STOPPED;
        if (joining > 0.0f) {
            if (raining) {
                joining = 0.0f;
                cover = Math.max(cover, fullCover(thunder) * Math.min(1.0f, rain / 0.5f));
                sinceRain = 0.0f;
                return;
            }
            joining -= Math.max(seconds, 0.0f);
        }
        if (seconds <= 0.0f) {
            return;
        }
        if (raining) {
            sinceRain = 0.0f;
            float full = fullCover(thunder);
            if (cover < full) {
                float pace = rain * (1.0f + THUNDER_PACE * thunder);
                cover = Math.min(full, cover + seconds * pace / FILL_SECONDS);
            } else {
                // Thunder has passed but the rain goes on: back down to a rain's puddles.
                cover = Math.max(full, cover - seconds / DRY_SECONDS);
            }
            return;
        }
        sinceRain += seconds;
        float drying = switch (fall) {
            case SNOW -> COLD_DRYING;
            case NONE -> ARID_DRYING;
            case RAIN -> sinceRain > LINGER_SECONDS ? 1.0f : 0.0f;
        };
        cover = Math.max(0.0f, cover - seconds * drying / DRY_SECONDS);
    }

    /** How far the puddles of a long rain grow in this much thunder. */
    static float fullCover(float thunder) {
        return 1.0f + THUNDER_EXTRA * thunder;
    }

    /** Dry ground again, as on entering another world or dimension; the next readings start afresh. */
    public void reset() {
        cover = 0.0f;
        sinceRain = Float.POSITIVE_INFINITY;
        joining = JOIN_SECONDS;
    }

    /** 0 dry, 1 a long rain's puddles, up to 1.3 in a long thunderstorm. */
    public float cover() {
        return cover;
    }
}
