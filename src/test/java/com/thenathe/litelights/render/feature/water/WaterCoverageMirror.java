package com.thenathe.litelights.render.feature.water;

/**
 * The maths of the water over the copied scene in {@code shaders/include/water/surface.glsl}
 * ({@code lite_water_over_scene}, {@code lite_water_coverage} and {@code lite_water_blend_over_scene}),
 * without reflections, foam or the depth handover to far terrain (depthLook is 1), so the tests can
 * check what each transparency path puts on screen. Keep the two in step.
 */
final class WaterCoverageMirror {
    /** The alpha of vanilla's water texture (180 of 255). */
    static final float TEXTURE_ALPHA = 180.0f / 255.0f;

    private WaterCoverageMirror() {
    }

    /** {@code lite_water_deepness}. */
    static float deepness(float thickness, boolean realistic) {
        return 1.0f - (float) Math.exp(-thickness / (realistic ? 7.0f : 10.0f));
    }

    /** {@code lite_water_depth_opacity}. */
    static float depthOpacity(float thickness, float alpha, boolean realistic) {
        return realistic
            ? mix(0.3f, 0.96f, 1.0f - (float) Math.exp(-thickness / 3.5f))
            : mix(alpha, 0.94f, deepness(thickness, realistic));
    }

    /** {@code lite_water_coverage}: the alpha every Improved Transparency stage gives a water top face. */
    static float coverage(float alpha, float thickness, boolean realistic, boolean biomeDepth) {
        if (!biomeDepth) {
            return alpha;
        }
        return Math.max(alpha, depthOpacity(thickness, alpha, realistic));
    }

    /** {@code lite_water_over_scene}: the colour the water should show over what lies beneath. */
    static float[] seen(float[] scene, float[] body, float[] hue, float light, boolean realistic, float alpha, float thickness) {
        float deep = deepness(thickness, realistic);
        float[] shaded = new float[3];
        for (int i = 0; i < 3; i++) {
            if (realistic) {
                float[] loss = {0.16f, 0.30f, 0.45f};
                float[] lift = {0.0f, 0.015f, 0.03f};
                shaded[i] = mix(body[i], (hue[i] * loss[i] + lift[i]) * light, deep);
            } else {
                shaded[i] = body[i] * mix(1.0f, 0.55f, deep);
            }
        }
        float opacity = depthOpacity(thickness, alpha, realistic);
        float strongest = Math.max(Math.max(hue[0], hue[1]), Math.max(hue[2], 0.05f));
        float tintShare = Math.min(1.0f, 0.2f + thickness / 8.0f) * (realistic ? 0.6f : 0.25f);
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            float throughWater = mix(1.0f, hue[i] / strongest, tintShare);
            result[i] = mix(scene[i] * throughWater, shaded[i], opacity);
        }
        return result;
    }

    /**
     * Improved Transparency: the colour stage solves for the colour that gives {@code seen} over
     * {@code behind} at {@code alpha} (clamped at zero, as the composite's 8-bit target would), and
     * the composite blends it over the frame with the same alpha.
     */
    static float[] improvedTransparency(float[] seen, float[] behind, float alpha) {
        float[] screen = new float[3];
        for (int i = 0; i < 3; i++) {
            float colour = Math.max((seen[i] - behind[i] * (1.0f - alpha)) / alpha, 0.0f);
            screen[i] = colour * alpha + behind[i] * (1.0f - alpha);
        }
        return screen;
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
