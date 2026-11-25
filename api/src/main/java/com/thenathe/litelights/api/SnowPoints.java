package com.thenathe.litelights.api;

/**
 * The visual snow's column points and share steps, shared by core's near snow and the far terrain
 * module's far snow so the two show the same columns white at the hand-over. Plain arithmetic in
 * 32-bit integers, which far terrain's shaders repeat exactly (include/farterrain/far_land_snow.glsl,
 * {@code lite_far_snow_point}).
 */
public final class SnowPoints {
    /** Steps a share is held to: 32. */
    public static final int STEPS = 32;

    private SnowPoints() {
    }

    /**
     * A block column's point, in [0, 1): evenly spread over columns and unrelated to its neighbours',
     * in 24-bit steps (exact as a float). A column holds a layer while that layer's share is past it.
     */
    public static float point(int x, int z) {
        int hash = mix(x * 0x9E3779B9 ^ mix(z ^ 0x632BE5AB));
        return (hash >>> 8) * 0x1.0p-24f;
    }

    /** A share held to the step at or below it, so a column never shows snow the real snow hasn't reached. */
    public static float quantize(double share) {
        if (!(share > 0.0)) {
            return 0.0f;
        }
        if (share >= 1.0) {
            return 1.0f;
        }
        return (float) Math.floor(share * STEPS) / STEPS;
    }

    /** A 32-bit integer hash (lowbias32): every input bit reaches every output bit. */
    public static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        value ^= value >>> 16;
        return value;
    }
}
