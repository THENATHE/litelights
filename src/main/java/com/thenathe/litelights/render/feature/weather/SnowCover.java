package com.thenathe.litelights.render.feature.weather;

/**
 * How much snow has gathered in the canopies and under the trees of snowy biomes: 0 is none, 1 as
 * much as they take. The shaders turn it into how much of each leaf block is white and how thick the
 * dusting under the trees is (include/weather/snow_cover.glsl).
 *
 * <p>The world's weather is one for every biome: while it rains anywhere, it snows in every cold
 * biome. So unlike the puddles, which follow the biome at the camera, this follows the world's rain
 * alone, and the shaders draw it only on columns whose own biome snows.
 *
 * <p>The rules (seconds of game time; nothing changes while the game is paused):
 * <ul>
 *   <li><b>Snowfall</b> builds it at a steady rate: from none to full in {@link #GROW_SECONDS} of
 *   steady snow, so the tops of the leaves turn white within the first minute and the canopies are
 *   full after two and a half. Light snow builds it more slowly, a blizzard half again as fast.</li>
 *   <li><b>After the snow</b> it stays as it is for {@link #HOLD_SECONDS} (half a day), then thaws
 *   at a steady rate, from full to nothing in {@link #THAW_SECONDS}. Snowy biomes stay cold, so only
 *   a long dry spell clears it: vanilla's clear spells last half a day to seven and a half, and
 *   snow that starts again before the thaw is over builds on what is left.</li>
 *   <li><b>Joining a world while it snows</b> (within its first {@link #JOIN_SECONDS}, as the
 *   server's weather can arrive a moment after the world), it starts where that snow would have
 *   left it, as it has been falling for a while already. Joining in clear weather it starts at
 *   none: there is no record of earlier snow.</li>
 * </ul>
 * Plain arithmetic, no game objects, so it is unit-tested on its own.
 */
public final class SnowCover {
    static final float GROW_SECONDS = 150.0f;
    static final float HOLD_SECONDS = 600.0f;
    static final float THAW_SECONDS = 600.0f;
    /** How much faster a snowy thunderstorm (a blizzard) builds it. */
    static final float THUNDER_PACE = 0.5f;
    /** Lighter snow than this counts as stopped. */
    static final float SNOW_STOPPED = 0.05f;
    static final float JOIN_SECONDS = 5.0f;

    private float cover;
    private float sinceSnow = Float.POSITIVE_INFINITY;
    private float joining = JOIN_SECONDS;

    /** Moves the snow on by {@code seconds}; {@code rain} and {@code thunder} are the world's levels, 0 to 1. */
    public void update(float seconds, float rain, float thunder) {
        boolean snowing = rain >= SNOW_STOPPED;
        if (joining > 0.0f) {
            if (snowing) {
                joining = 0.0f;
                cover = Math.max(cover, Math.min(1.0f, rain / 0.5f));
                sinceSnow = 0.0f;
                return;
            }
            joining -= Math.max(seconds, 0.0f);
        }
        if (seconds <= 0.0f) {
            return;
        }
        if (snowing) {
            sinceSnow = 0.0f;
            float pace = rain * (1.0f + THUNDER_PACE * thunder);
            cover = Math.min(1.0f, cover + seconds * pace / GROW_SECONDS);
            return;
        }
        float before = sinceSnow;
        sinceSnow += seconds;
        float thawing = sinceSnow - Math.max(before, HOLD_SECONDS);
        if (thawing > 0.0f) {
            cover = Math.max(0.0f, cover - thawing / THAW_SECONDS);
        }
    }

    /** No snow, as on entering another world or dimension; the next readings start afresh. */
    public void reset() {
        cover = 0.0f;
        sinceSnow = Float.POSITIVE_INFINITY;
        joining = JOIN_SECONDS;
    }

    /** 0 none, 1 full canopies. */
    public float cover() {
        return cover;
    }
}
