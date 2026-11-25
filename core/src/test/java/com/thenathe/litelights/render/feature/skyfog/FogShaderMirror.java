package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.render.LiteMath;

/**
 * The fog formulas of {@code shaders/include/skyfog/atmosphere.glsl} in Java, so the tests can check
 * them. Keep the two in step.
 */
final class FogShaderMirror {
    private FogShaderMirror() {
    }

    /**
     * Optical depth of an exponential height haze along the straight line from the camera to a
     * point: {@code integral of density * exp(-falloff * h)} over the path. Heights below the haze
     * base count as the base (the haze doesn't keep thickening underground).
     *
     * @param distance length of the path in blocks
     * @param cameraHeight camera height above the haze base
     * @param rise how far the point is above the camera (negative below)
     * @param density density per block at the base
     * @param falloff how quickly the density falls with height, per block (0 = uniform)
     */
    public static float hazeDepth(float distance, float cameraHeight, float rise, float density, float falloff) {
        float startHeight = Math.max(cameraHeight, 0.0f);
        float endHeight = Math.max(cameraHeight + rise, 0.0f);
        float exponent = falloff * (endHeight - startHeight);
        // (exp(-f start) - exp(-f end)) / (f (end - start)), both exponentials at most 1 so nothing
        // overflows from high up; its series near 0 where the division would lose precision.
        float atStart = (float) Math.exp(-falloff * startHeight);
        float shape = Math.abs(exponent) > 1.0e-3f
            ? (atStart - (float) Math.exp(-falloff * endHeight)) / exponent
            : atStart * (1.0f - 0.5f * exponent);
        return density * distance * shape;
    }

    /** How much of what lies behind a medium of this optical depth is hidden by it, 0..1. */
    public static float coverage(float opticalDepth) {
        return 1.0f - (float) Math.exp(-Math.max(opticalDepth, 0.0f));
    }

    /** Linear fog between two distances, as vanilla's render-distance fog. */
    public static float linearFog(float distance, float start, float end) {
        if (end <= start) {
            return distance >= end ? 1.0f : 0.0f;
        }
        return LiteMath.clamp01((distance - start) / (end - start));
    }

    /**
     * Where vanilla's render-distance fog starts for a render distance in blocks: the last tenth of
     * the distance, at least 4 and at most 64 blocks.
     */
    public static float renderFogStart(float renderDistanceBlocks) {
        return renderDistanceBlocks - Math.clamp(renderDistanceBlocks / 10.0f, 4.0f, 64.0f);
    }
}
