package com.thenathe.litelights.render.feature.particles;

/**
 * Where haze puffs gather (fog097): a slow value noise over the ground, 32 blocks to a cell, that
 * drifts with the haze's own drift. It repeats every 2048 blocks, as the drift wraps, so the
 * pattern never jumps when the drift does. A puff spawning in a thin spot is mostly turned away and one in
 * a thick spot always kept, and the spawners spawn {@link #RATE_SCALE} times as many to make up
 * for those turned away, so the air holds about as much haze as before, in clumps and gaps instead
 * of one even sheet.
 */
public final class HazeClumps {
    /** Blocks to a noise cell. */
    static final double CELL = 32.0;
    /** Cells wrap at 64, every 2048 blocks. */
    private static final long CELL_MASK = 63;
    /** The least share of puffs kept in the thinnest spots, so nowhere is quite bare. */
    static final float FLOOR = 0.12f;
    /** The mean share kept is about 1 / this, so the spawners multiply their rates by it. */
    public static final float RATE_SCALE = 1.8f;

    private HazeClumps() {
    }

    /** The share of puffs kept at a world position, {@link #FLOOR} to 1, for a drift of the pattern. */
    public static float share(double x, double z, double driftX, double driftZ) {
        double u = (x - driftX) / CELL;
        double v = (z - driftZ) / CELL;
        double cellU = Math.floor(u);
        double cellV = Math.floor(v);
        float fu = smooth((float) (u - cellU));
        float fv = smooth((float) (v - cellV));
        long i = (long) cellU;
        long j = (long) cellV;
        float bottom = lerp(hash(i, j), hash(i + 1, j), fu);
        float top = lerp(hash(i, j + 1), hash(i + 1, j + 1), fu);
        float noise = lerp(bottom, top, fv);
        float shape = smooth(Math.clamp((noise - 0.2f) / 0.6f, 0.0f, 1.0f));
        return FLOOR + (1.0f - FLOOR) * shape;
    }

    /** Whether to keep a puff here, for a random number 0..1. */
    public static boolean keep(double x, double z, double driftX, double driftZ, float random) {
        return random < share(x, z, driftX, driftZ);
    }

    private static float smooth(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** 0..1 from a cell's two coordinates. */
    static float hash(long i, long j) {
        long h = (i & CELL_MASK) * 0x9E3779B97F4A7C15L ^ (j & CELL_MASK) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }
}
