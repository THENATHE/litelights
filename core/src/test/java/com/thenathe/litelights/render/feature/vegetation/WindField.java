package com.thenathe.litelights.render.feature.vegetation;

/**
 * The sway wind, the same maths as {@code shaders/include/hooks/vegetation.glsl} in Java, so its
 * continuity can be unit-tested. Keep the two in step: every constant and wave here has its twin
 * there. The heading is the world's wind direction, which the shader reads from the frame block.
 *
 * <p>Positions are "wrapped world" positions: the camera block's coordinates modulo 256 plus the
 * position relative to that block, which is what the shader can build exactly at any distance from
 * the world origin. Every wave has a whole number of cycles per 256 blocks and per hour, so the
 * wrap and the shader clock's wrap at 3600 s are invisible.
 */
public final class WindField {
    public static final int PERIOD_BLOCKS = 256;
    public static final float PERIOD_SECONDS = 3600.0f;
    public static final float PLANT_AMPLITUDE = 0.09f;
    public static final float LEAF_AMPLITUDE = 0.035f;
    public static final float FADE_START = 64.0f;
    public static final float FADE_END = 96.0f;

    private static final float TAU = (float) (Math.PI * 2.0);

    private WindField() {
    }

    /** The wrapped world position the shader builds from the camera block and a block-local position. */
    public static float wrap(int cameraBlock, float blockLocal) {
        return (cameraBlock & (PERIOD_BLOCKS - 1)) + blockLocal;
    }

    /** Horizontal bend of grass and plants per block of height: {x, y, z}. */
    public static float[] plant(float x, float z, float seconds, float heading) {
        float[] gust = gust(x, z, seconds, heading);
        float acrossX = -gust[1];
        float acrossZ = gust[0];
        float swing = sin(wave(19, 13, x, z) + rate(1260) * seconds);
        float flutter = sin(wave(-17, 23, x, z) + rate(1776) * seconds);
        float lean = gust[2] * (0.55f + 0.45f * swing);
        float side = gust[2] * 0.2f * flutter;
        return new float[] {gust[0] * lean + acrossX * side, 0.0f, gust[1] * lean + acrossZ * side};
    }

    /** Leaf motion (before the leaf amplitude): {x, y, z}. */
    public static float[] leaves(float x, float y, float z, float seconds, float heading) {
        float[] gust = gust(x, z, seconds, heading);
        float along = sin(wave3(23, 17, 31, x, y, z) + rate(917) * seconds);
        float lift = sin(wave3(-19, 37, 27, x, y, z) + rate(1318) * seconds);
        float across = sin(wave3(29, -23, -41, x, y, z) + rate(1121) * seconds);
        float horizontalX = gust[0] * (0.4f + 0.6f * along) - gust[1] * 0.5f * across;
        float horizontalZ = gust[1] * (0.4f + 0.6f * along) + gust[0] * 0.5f * across;
        return new float[] {horizontalX * gust[2], 0.4f * lift * gust[2], horizontalZ * gust[2]};
    }

    /** The prevailing wind: {direction x, direction z, gust strength 0.1..1}. */
    public static float[] gust(float x, float z, float seconds, float heading) {
        float wave = 0.6f * sin(wave(5, 2, x, z) - rate(378) * seconds)
            + 0.4f * sin(wave(3, 7, x, z) - rate(429) * seconds);
        return new float[] {cos(heading), sin(heading), 0.55f + 0.45f * wave};
    }

    /** Fade with distance from the camera: 1 near, 0 from {@link #FADE_END} on. */
    public static float fade(float distance) {
        float t = Math.clamp((distance - FADE_START) / (FADE_END - FADE_START), 0.0f, 1.0f);
        return 1.0f - t * t * (3.0f - 2.0f * t);
    }

    /** A little stronger in rain, more again in thunder. */
    public static float weather(float rain, float thunder) {
        return 1.0f + 0.35f * rain + 0.35f * thunder;
    }

    /**
     * How far up its plant a vertex is, in blocks (see lite_plant_height): 0 at the foot, about 1
     * at the top of a one-block plant, about 2 at the top of a two-block one.
     */
    public static float plantHeight(float blockLocalY, boolean topOfSprite, boolean upperHalf) {
        float plantBase = topOfSprite ? (float) Math.ceil(blockLocalY - 0.25f) - 1.0f : (float) Math.floor(blockLocalY + 0.25f);
        float height = blockLocalY - plantBase;
        if (upperHalf) {
            return 1.0f + height;
        }
        if (!topOfSprite && height < 0.3f) {
            return 0.0f;
        }
        return Math.max(height, 0.0f);
    }

    private static float wave(int cyclesX, int cyclesZ, float x, float z) {
        float scale = TAU / PERIOD_BLOCKS;
        return cyclesX * scale * x + cyclesZ * scale * z;
    }

    private static float wave3(int cyclesX, int cyclesY, int cyclesZ, float x, float y, float z) {
        float scale = TAU / PERIOD_BLOCKS;
        return cyclesX * scale * x + cyclesY * scale * y + cyclesZ * scale * z;
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
