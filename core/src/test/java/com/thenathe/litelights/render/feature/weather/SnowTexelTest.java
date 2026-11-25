package com.thenathe.litelights.render.feature.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How white a snowy leaf's texel is (include/weather/snow_cover.glsl, lite_snow_texel), mirrored
 * here. Keep the mirror in step with the shader.
 */
class SnowTexelTest {
    private static final float SOFT_START = 24.0f;
    private static final float SOFT_END = 80.0f;

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float texel(float need, float amount, float distance) {
        float soft = Math.min(0.02f + 0.28f * smoothstep(SOFT_START, SOFT_END, distance), need);
        return smoothstep(need - soft, need + soft, amount);
    }

    @Test
    void noSnowLeavesEveryTexelBareAtAnyDistance() {
        // The smallest need is a bright top face's, 0.12; the far softness reaches 0.3.
        for (float need = 0.12f; need <= 2.0f; need += 0.01f) {
            for (float distance = 0.0f; distance <= 300.0f; distance += 4.0f) {
                assertEquals(0.0f, texel(need, 0.0f, distance), "need " + need + " at " + distance);
            }
        }
    }

    @Test
    void snowStillComesInSmoothlyAndFullSnowIsWhite() {
        float far = 200.0f;
        assertTrue(texel(0.12f, 0.01f, far) < 0.01f, "a trace of snow is a trace far away");
        assertTrue(texel(0.12f, 0.12f, far) > 0.4f);
        assertEquals(1.0f, texel(0.12f, 1.3f, far), 1.0e-6f);
        assertEquals(1.0f, texel(0.67f, 1.3f, 10.0f), 1.0e-6f, "near, as before");
    }
}
