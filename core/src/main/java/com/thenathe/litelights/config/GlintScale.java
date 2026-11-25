package com.thenathe.litelights.config;

/**
 * What the four glint sliders mean (0.9.7, followb097). Each slider runs from 0 to 1 with its
 * default at 0.5, and is turned here into the two numbers the glint shader works with:
 *
 * <ul>
 *   <li><b>softness</b> on the scale of the single Glint softness slider before 0.9.7, where 0 was
 *   small sharp sparkles and 1 a wide soft sheen. Below 0 the sparkles get rarer, smaller and
 *   crisper; above 1 the sheen spreads wider still (light_post/surface_light.glsl).</li>
 *   <li><b>intensity</b>, a multiple of the glint's brightness before 0.9.7.</li>
 * </ul>
 *
 * Water (the water surface, and everything that glints while the camera is under water): softness
 * 0.5 is the old 0.80, 0 the old 0, 1 past the old 1; intensity 0.5 is as bright as before, 1 twice
 * as bright. Ground (ice and snow, metal and glass, sand and stone): softness 0.5 is the old 0, 0
 * crisper than anything before, 1 the old 1; intensity 0.5 is half as bright as before, 1 as bright.
 * At 0 either intensity puts its glint out.
 */
public final class GlintScale {
    /** The old softness the water's default stands for: the setting the user played with. */
    public static final float WATER_DEFAULT_SOFTNESS = 0.80f;
    /** The softest water: half a step past the old slider's end. */
    public static final float WATER_SOFTEST = 1.5f;
    /** The crispest ground: the old scale carried on as far below 0 as the old slider went above it. */
    public static final float GROUND_CRISPEST = -1.0f;

    private GlintScale() {
    }

    public static float waterSoftness(float slider) {
        float position = clamp01(slider);
        if (position <= 0.5f) {
            return position * 2.0f * WATER_DEFAULT_SOFTNESS;
        }
        return WATER_DEFAULT_SOFTNESS + (position - 0.5f) * 2.0f * (WATER_SOFTEST - WATER_DEFAULT_SOFTNESS);
    }

    public static float groundSoftness(float slider) {
        return (clamp01(slider) - 0.5f) * 2.0f;
    }

    public static float waterIntensity(float slider) {
        return clamp01(slider) * 2.0f;
    }

    public static float groundIntensity(float slider) {
        return clamp01(slider);
    }

    /**
     * Where the water softness slider stands for a softness on the old scale (0 to 1): the inverse
     * of {@link #waterSoftness}, for carrying an old Glint softness setting over.
     */
    public static float waterSliderForOldSoftness(float oldSoftness) {
        float softness = Math.clamp(oldSoftness, 0.0f, 1.0f);
        if (softness <= WATER_DEFAULT_SOFTNESS) {
            return softness / (2.0f * WATER_DEFAULT_SOFTNESS);
        }
        return 0.5f + (softness - WATER_DEFAULT_SOFTNESS) / (2.0f * (WATER_SOFTEST - WATER_DEFAULT_SOFTNESS));
    }

    /**
     * A softness and an intensity in one float, exactly: softness in hundredths from -1 in the low
     * byte, intensity in hundredths above it. Both fit a byte (softness up to 1.55, intensity up to
     * 2.55) and the whole stays below 2^16. The shader unpacks it in lite_lp_glint_tuning.
     */
    public static int pack(float softness, float intensity) {
        int softnessCode = Math.clamp(Math.round((softness + 1.0f) * 100.0f), 0, 255);
        int intensityCode = Math.clamp(Math.round(intensity * 100.0f), 0, 255);
        return softnessCode | intensityCode << 8;
    }

    private static float clamp01(float value) {
        return Math.clamp(value, 0.0f, 1.0f);
    }
}
