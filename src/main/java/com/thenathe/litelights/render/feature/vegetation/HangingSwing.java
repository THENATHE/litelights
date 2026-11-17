package com.thenathe.litelights.render.feature.vegetation;

import com.thenathe.litelights.render.feature.particles.LeafWind;

/**
 * How hanging lanterns, chains and hanging signs swing in the wind: each hanging column (a lantern,
 * hanging sign or loose chain end with the vertical chain above it) is one rigid pendulum that turns
 * about the top of its column. The terrain shaders move the blocks (shaders/include/vegetation/hanging.glsl)
 * and a hanging sign's text turns with the same maths here, so the two must stay in step: every
 * constant has its twin there.
 *
 * <p>A column is four ints, as the frame block holds it: its block x and z, its bottom in sixteenths
 * of a block with the swing's cycles per hour below it ({@link #packBottom}), and the pivot height
 * in sixteenths with the swing's mode ({@link #packPivot}). The cycles are worked out here once,
 * from the column's length, so the shader and Java can't round them differently.
 *
 * <p>Every swing has a whole number of cycles per hour and the gusts repeat every 256 blocks, so the
 * shader clock's wrap at 3600 s and the world position's wrap never show.
 */
public final class HangingSwing {
    /** Columns the frame block holds; the nearest are kept. */
    public static final int MAX_COLUMNS = 64;
    /** Rows in the cell mask: one per {@code z & 31}, one bit per {@code x & 31}. */
    public static final int CELL_ROWS = 32;
    /** Mode of a column that swings every way; constrained ones are {@code 1 + rotation segment}. */
    public static final int FREE = 0;

    /** The swing fades out over the same distances as the plants' sway. */
    public static final float FADE_START = 64.0f;
    public static final float FADE_END = 96.0f;

    /** Sets how fast a pendulum of a given length swings: a little under real gravity, so short ones aren't twitchy. */
    static final float GRAVITY = 7.0f;
    /** The swing's amplitude in radians on a calm day, and what the strongest storm adds, for a short pendulum. */
    static final float CALM_SWING = 0.05f;
    static final float STORM_SWING = 0.22f;
    /** How far a storm's gusts lean a short pendulum over with the wind, in radians. */
    static final float LEAN = 0.12f;
    /** The sideways swing, as a share of the swing along the wind. */
    static final float ACROSS = 0.45f;
    /** Pendulums up to this long swing through the full angle; longer ones through less, so their ends don't fly about. */
    static final float SHORT_PENDULUM = 0.6f;

    private static final int MAX_CYCLES = 4095;
    private static final float TAU = (float) (Math.PI * 2.0);
    private static final float PERIOD_SECONDS = 3600.0f;

    private HangingSwing() {
    }

    /** A column's bottom (in sixteenths of a block) and its swing's cycles per hour, as one int. */
    public static int packBottom(int bottomSixteenths, int cyclesPerHour) {
        return bottomSixteenths << 12 | cyclesPerHour;
    }

    /** A column's pivot height (in sixteenths of a block) and its mode ({@link #FREE} or 1 + rotation segment), as one int. */
    public static int packPivot(int pivotSixteenths, int mode) {
        return pivotSixteenths << 5 | mode;
    }

    public static int bottomSixteenths(int packedBottom) {
        return packedBottom >> 12;
    }

    public static int cycles(int packedBottom) {
        return packedBottom & MAX_CYCLES;
    }

    public static int pivotSixteenths(int packedPivot) {
        return packedPivot >> 5;
    }

    public static int mode(int packedPivot) {
        return packedPivot & 31;
    }

    /** From the pivot to the middle of the weight, in blocks: a lantern's body sits in the lower part of its block. */
    static float pendulumLength(int spanSixteenths) {
        return Math.max(spanSixteenths / 16.0f - 0.4f, 0.3f);
    }

    /**
     * Whole swings per hour for a column {@code spanSixteenths} long: a pendulum's own rate, a few
     * per cent faster or slower per column so a row of lanterns drifts out of step.
     */
    public static int cyclesPerHour(int spanSixteenths, int x, int z) {
        double period = TAU * Math.sqrt(pendulumLength(spanSixteenths) / GRAVITY);
        int jitter = hash(x, z) >>> 20 & 255;
        double cycles = PERIOD_SECONDS / period * (0.94 + 0.12 * jitter / 255.0);
        return (int) Math.clamp(Math.floor(cycles + 0.5), 1, MAX_CYCLES);
    }

    /** The shader's integer hash of a column (lite_swing_hash); Java's int arithmetic wraps as GLSL's uint does. */
    static int hash(int x, int z) {
        int h = x * 0x9E3779B1 ^ z * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h;
    }

    /**
     * How far a column leans, in radians, as a horizontal vector: the direction its bottom swings
     * toward times the angle, written to {@code out[0]} (x) and {@code out[1]} (z). {@code strength}
     * is the wind from 0 (calm) to 1 (the strongest storm), {@code heading} its direction.
     */
    public static void tilt(int x, int z, int packedBottom, int packedPivot, float seconds, float heading, float strength, float[] out) {
        int span = pivotSixteenths(packedPivot) - bottomSixteenths(packedBottom);
        float reach = (float) Math.sqrt(SHORT_PENDULUM / Math.max(pendulumLength(span), SHORT_PENDULUM));
        int cycles = cycles(packedBottom);
        int hash = hash(x, z);
        float phase = (hash & 1023) * (TAU / 1024.0f);
        float acrossPhase = (hash >>> 10 & 1023) * (TAU / 1024.0f);
        float along = sin(rate(cycles) * seconds + phase);
        float across = sin(rate(cycles + (cycles >> 3)) * seconds + acrossPhase);

        float gust = LeafWind.gust((x & 255) + 0.5, (z & 255) + 0.5, seconds);
        float gustiness = 0.3f + 0.5f * strength;
        float swing = reach * (CALM_SWING + STORM_SWING * strength) * (1.0f - gustiness + gustiness * gust);
        float lean = reach * LEAN * strength * gust;
        float windX = cos(heading);
        float windZ = sin(heading);
        float tiltX = windX * (lean + swing * along) - windZ * (swing * ACROSS * across);
        float tiltZ = windZ * (lean + swing * along) + windX * (swing * ACROSS * across);

        int mode = mode(packedPivot);
        if (mode != FREE) {
            // A sign hung by two chains, or from its bar, swings only toward and away from its face.
            float angle = (mode - 1) * (TAU / 16.0f);
            float normalX = -sin(angle);
            float normalZ = cos(angle);
            float toward = tiltX * normalX + tiltZ * normalZ;
            tiltX = normalX * toward;
            tiltZ = normalZ * toward;
        }
        out[0] = tiltX;
        out[1] = tiltZ;
    }

    /** Fade with the distance from the camera to the column's bottom: 1 near, 0 from {@link #FADE_END} on. */
    public static float fade(float distance) {
        float t = Math.clamp((distance - FADE_START) / (FADE_END - FADE_START), 0.0f, 1.0f);
        return 1.0f - t * t * (3.0f - 2.0f * t);
    }

    /**
     * Turns {@code point} (relative to the pivot) by a tilt, the way the shader does: about the
     * horizontal axis at right angles to the tilt, so a point below the pivot moves toward it.
     * Returns the turned point; for tests, and for anything that must sit where the shader puts it.
     */
    public static float[] turn(float tiltX, float tiltZ, float x, float y, float z) {
        float angle = (float) Math.sqrt(tiltX * tiltX + tiltZ * tiltZ);
        if (angle < 1.0e-5f) {
            return new float[] {x, y, z};
        }
        float axisX = -tiltZ / angle;
        float axisZ = tiltX / angle;
        float c = cos(angle);
        float s = sin(angle);
        // Rodrigues' rotation about (axisX, 0, axisZ).
        float crossX = -axisZ * y;
        float crossY = axisZ * x - axisX * z;
        float crossZ = axisX * y;
        float along = (axisX * x + axisZ * z) * (1.0f - c);
        return new float[] {
            x * c + crossX * s + axisX * along,
            y * c + crossY * s,
            z * c + crossZ * s + axisZ * along
        };
    }

    private static float rate(int cyclesPerHour) {
        return cyclesPerHour * (TAU / PERIOD_SECONDS);
    }

    private static float sin(float radians) {
        return (float) Math.sin(radians);
    }

    private static float cos(float radians) {
        return (float) Math.cos(radians);
    }
}
