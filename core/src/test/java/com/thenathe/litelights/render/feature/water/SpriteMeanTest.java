package com.thenathe.litelights.render.feature.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class SpriteMeanTest {
    private static int abgr(int alpha, int blue, int green, int red) {
        return alpha << 24 | blue << 16 | green << 8 | red;
    }

    @Test
    void averagesEveryChannel() {
        int[] pixels = {abgr(255, 0, 0, 255), abgr(255, 255, 0, 0)};
        assertArrayEquals(new float[] {0.5f, 0.0f, 0.5f}, SpriteMean.of(pixels), 1.0e-6f);
    }

    @Test
    void clearTexelsDontDarkenIt() {
        int[] pixels = {abgr(255, 200, 200, 200), abgr(0, 0, 0, 0), abgr(0, 0, 0, 0)};
        float grey = 200 / 255.0f;
        assertArrayEquals(new float[] {grey, grey, grey}, SpriteMean.of(pixels), 1.0e-6f);
    }

    @Test
    void anEmptyTextureFallsBackToVanillasGrey() {
        assertArrayEquals(SpriteMean.FALLBACK, SpriteMean.of(new int[] {0, 0}), 0.0f);
    }
}
