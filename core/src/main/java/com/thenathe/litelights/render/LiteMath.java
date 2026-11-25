package com.thenathe.litelights.render;

/** Small maths helpers shared by every stream. Pure functions, any thread. */
public final class LiteMath {
    private LiteMath() {
    }

    public static float clamp01(float value) {
        return Math.clamp(value, 0.0f, 1.0f);
    }

    public static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    /** GLSL's smoothstep: 0 below {@code edge0}, 1 above {@code edge1}, an S-curve between. */
    public static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0f - 2.0f * t);
    }

    /** The red channel of a packed 0xRRGGBB colour, 0..1. */
    public static float red(int rgb) {
        return ((rgb >> 16) & 0xFF) / 255.0f;
    }

    public static float green(int rgb) {
        return ((rgb >> 8) & 0xFF) / 255.0f;
    }

    public static float blue(int rgb) {
        return (rgb & 0xFF) / 255.0f;
    }

    /** Packs 0..1 channels (clamped) into 0xRRGGBB. */
    public static int packRgb(float red, float green, float blue) {
        int r = Math.round(clamp01(red) * 255.0f);
        int g = Math.round(clamp01(green) * 255.0f);
        int b = Math.round(clamp01(blue) * 255.0f);
        return (r << 16) | (g << 8) | b;
    }

    /** Mixes two packed 0xRRGGBB colours channel by channel. */
    public static int mixRgb(int from, int to, float amount) {
        return packRgb(lerp(red(from), red(to), amount), lerp(green(from), green(to), amount), lerp(blue(from), blue(to), amount));
    }
}
