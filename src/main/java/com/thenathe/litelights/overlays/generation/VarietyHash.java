package com.thenathe.litelights.overlays.generation;

import com.thenathe.litelights.util.Hashing;

/**
 * Picks variety pieces from world positions. Positions are in half blocks (twice the block
 * coordinate, plus one for a face centre), so every block corner and every edge midpoint has its
 * own whole-number point that all faces touching it agree on.
 *
 * <p>The result depends on the position alone, never on the block or the source, so the two
 * halves of a blended seam and the layers of one source always pick matching pieces.
 */
public final class VarietyHash {
    private static final long CORNER_SALT = 0x2C1B3C6DL;
    private static final long WAVE_SALT = 0x297A2D39L;

    private VarietyHash() {
    }

    /** The depth class of the block corner at a half-block point. */
    public static int cornerClass(int x, int y, int z, int classes) {
        return classes <= 1 ? 0 : pick(x, y, z, CORNER_SALT, classes);
    }

    /** The wave of the edge whose midpoint is at a half-block point. */
    public static int wave(int x, int y, int z, int waves) {
        return waves <= 1 ? 0 : pick(x, y, z, WAVE_SALT, waves);
    }

    private static int pick(int x, int y, int z, long salt, int count) {
        long h = Hashing.mix64(x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ salt);
        return (int) Math.floorMod(h, (long) count);
    }
}
