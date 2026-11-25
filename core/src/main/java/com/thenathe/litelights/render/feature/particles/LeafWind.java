package com.thenathe.litelights.render.feature.particles;

/**
 * How falling leaves ride LiteLights' wind ({@code weather.Wind}): how hard it blows for them, the
 * gust bands that roll across the land, how much of the wind a leaf is carried at, how many more
 * leaves let go in a gust, and how a gust holds a leaf up.
 *
 * <p>The gust bands are the ones the swaying plants and leaves feel ({@code lite_wind_gust} in
 * shaders/include/hooks/vegetation.glsl), so a canopy that bends in a gust is also the one that
 * sheds its leaves. Pure functions, so they are unit-tested.
 */
public final class LeafWind {
    /** The wind's speed on a calm day, in blocks a tick (Wind gives 0.025 to 0.06 without storms). */
    static final float CALM = 0.04f;
    /** The wind's speed in the strongest storms, in blocks a tick. */
    static final float STORM = 0.3f;
    /** In a storm's strongest gusts this many times as many leaves let go as on a calm day. */
    static final float MOST_LEAVES = 3.0f;
    /** Ticks a falling leaf takes to drop clear of its canopy before the wind takes it fully (about half a block). */
    static final int LETTING_GO_TICKS = 24;
        /** A gust holds a leaf up by at most this share of its fall, so it never climbs away. */
    private static final double MOST_LIFT = 0.6;

    private static final double TAU = Math.PI * 2.0;
    private static final double PERIOD_BLOCKS = 256.0;
    private static final double PERIOD_SECONDS = 3600.0;

    private LeafWind() {
    }

    /** 0 on a calm day to 1 in the strongest storm, from the wind's speed in blocks a tick. */
    public static float strength(float windX, float windZ) {
        float speed = (float) Math.sqrt(windX * windX + windZ * windZ);
        return Math.clamp((speed - CALM) / (STORM - CALM), 0.0f, 1.0f);
    }

    /**
     * The gust strength at a world position, 0.1 to 1, at the shaders' clock (seconds wrapped at an
     * hour). The same two rolling waves as {@code lite_wind_gust}: whole cycles per 256 blocks and
     * per hour, so neither the world position nor the clock needs wrapping here.
     */
    public static float gust(double x, double z, double seconds) {
        double wave = 0.6 * Math.sin((5.0 * x + 2.0 * z) * (TAU / PERIOD_BLOCKS) - 378.0 * (TAU / PERIOD_SECONDS) * seconds)
            + 0.4 * Math.sin((3.0 * x + 7.0 * z) * (TAU / PERIOD_BLOCKS) - 429.0 * (TAU / PERIOD_SECONDS) * seconds);
        return (float) (0.55 + 0.45 * wave);
    }

    /**
     * The share of the wind's speed a falling leaf is carried at: a fifth of it, and less between
     * gusts, on a calm day, so leaves fall nearly straight; most of it in a storm's gusts.
     */
    public static float carry(float strength, float gust) {
        return (0.2f + 0.7f * strength) * (0.4f + 0.6f * gust);
    }

    /**
     * How much of its carry a leaf {@code ageTicks} old takes: none as it lets go, all of it from
     * {@link #LETTING_GO_TICKS} on. A leaf first drops clear of its canopy; blown sideways at once it
     * would hit the leaves beside it, which ends a falling leaf.
     */
    public static float lettingGo(int ageTicks) {
        float t = Math.clamp(ageTicks / (float) LETTING_GO_TICKS, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /** How many times as many leaves let go as vanilla drops: 1 on a calm day, up to {@link #MOST_LEAVES} in a storm's gusts. */
    public static float release(float strength, float gust) {
        return 1.0f + (MOST_LEAVES - 1.0f) * strength * gust;
    }

    /**
     * How far a gust holds a falling leaf up this tick, in blocks, given how far it falls
     * ({@code fall}, negative going down). Nothing on a calm day or for a leaf that isn't falling.
     */
    public static double lift(float strength, float gust, double fall) {
        if (fall >= 0.0) {
            return 0.0;
        }
        return Math.min(0.012 * strength * gust, -fall * MOST_LIFT);
    }
}
