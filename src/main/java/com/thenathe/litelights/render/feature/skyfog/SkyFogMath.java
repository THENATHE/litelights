package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.render.LiteMath;

/**
 * The colour and blend maths behind the sky and fog, kept free of game classes so it can be unit
 * tested. The fog formulas themselves live in {@code skyfog/atmosphere.glsl}; their Java twins,
 * which only the tests use, are in the tests' {@code FogShaderMirror}.
 */
public final class SkyFogMath {
    private SkyFogMath() {
    }

    /** Rec. 709 luminance of a colour. */
    public static float luminance(float red, float green, float blue) {
        return 0.2126f * red + 0.7152f * green + 0.0722f * blue;
    }

    /**
     * How far to move toward a target this frame for an exponential approach that is independent
     * of frame rate: after {@code seconds} the remaining gap is {@code exp(-rate * seconds)} of the
     * original.
     */
    public static float approachFactor(float ratePerSecond, float seconds) {
        if (seconds <= 0.0f) {
            return 0.0f;
        }
        return 1.0f - (float) Math.exp(-ratePerSecond * seconds);
    }

    /**
     * Daylight from the sine of the sun's elevation: 0 once the sun is well below the horizon, 1 a
     * little after sunrise. Matches the curve the fog and sky shaders assume.
     */
    public static float daylight(float sunSine) {
        return LiteMath.smoothstep(-0.18f, 0.22f, sunSine);
    }

    /** Peaks at 1 while the sun crosses the horizon and fades out about 15 degrees either side. */
    public static float twilight(float sunSine) {
        float distance = sunSine / 0.16f;
        return (float) Math.exp(-distance * distance);
    }

    /**
     * A haze colour for a biome LiteLights has no entry for, from the biome's own colours: its fog
     * colour carries most of it, its sky colour a third and its water colour a little, so an
     * alien-green biome gets a greenish haze and a dusty one a dusty haze. The result keeps the
     * hue but is held within a sensible brightness and saturation, because haze over the whole
     * view at full strength must never be neon or black.
     *
     * @return packed 0xRRGGBB
     */
    public static int derivedHazeColor(int fogRgb, int skyRgb, int waterRgb) {
        float red = 0.55f * LiteMath.red(fogRgb) + 0.33f * LiteMath.red(skyRgb) + 0.12f * LiteMath.red(waterRgb);
        float green = 0.55f * LiteMath.green(fogRgb) + 0.33f * LiteMath.green(skyRgb) + 0.12f * LiteMath.green(waterRgb);
        float blue = 0.55f * LiteMath.blue(fogRgb) + 0.33f * LiteMath.blue(skyRgb) + 0.12f * LiteMath.blue(waterRgb);
        float luma = luminance(red, green, blue);
        // Pull saturation in to at most about half of what it was.
        float saturation = 0.55f;
        red = LiteMath.lerp(luma, red, saturation);
        green = LiteMath.lerp(luma, green, saturation);
        blue = LiteMath.lerp(luma, blue, saturation);
        // Keep daytime haze between a deep and a pale grey's brightness.
        float targetLuma = Math.clamp(luma, 0.45f, 0.88f);
        float scale = luma > 1.0e-4f ? targetLuma / luma : 1.0f;
        if (luma <= 1.0e-4f) {
            red = green = blue = targetLuma;
        }
        return LiteMath.packRgb(red * scale, green * scale, blue * scale);
    }

}
