package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.thenathe.litelights.api.SnowPoints;
import org.junit.jupiter.api.Test;

/**
 * far_snow.glsl's layers, mirrored here as the shader works them out (unsigned 32-bit arithmetic),
 * against the near snow's points and rule: the same shares give the same layers at the same column.
 */
class FarSnowLayersTest {
    private static final long MASK = 0xFFFFFFFFL;

    private static long mix(long value) {
        value &= MASK;
        value ^= value >>> 16;
        value = value * 0x7FEB352DL & MASK;
        value ^= value >>> 15;
        value = value * 0x846CA68BL & MASK;
        value ^= value >>> 16;
        return value;
    }

    /** lite_far_snow_point: uint(column.x) * 0x9E3779B9u ^ mix(uint(column.y) ^ 0x632BE5ABu), mixed. */
    private static float shaderPoint(int x, int z) {
        long hash = mix((x & MASK) * 0x9E3779B9L & MASK ^ mix((z & MASK) ^ 0x632BE5ABL));
        return (float) (hash >>> 8) * (1.0f / 16777216.0f);
    }

    /** lite_far_snow_layers between the lines: one layer for each share past the point. */
    private static int shaderLayers(float[] shares, float point) {
        int layers = 0;
        for (float share : shares) {
            layers += point < share ? 1 : 0;
        }
        return layers;
    }

    @Test
    void theShadersPointIsTheNearSnowsPoint() {
        for (int x = -3000; x <= 3000; x += 37) {
            for (int z = -3000; z <= 3000; z += 41) {
                assertEquals(SnowPoints.point(x, z), shaderPoint(x, z), "column " + x + ", " + z);
            }
        }
        assertEquals(SnowPoints.point(Integer.MAX_VALUE, Integer.MIN_VALUE), shaderPoint(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void theSameSharesGiveTheSameLayersFarAndNear() {
        float[] shares = {SnowPoints.quantize(0.97), SnowPoints.quantize(0.4), SnowPoints.quantize(0.05), 0.0f};
        for (int x = 0; x < 200; x++) {
            for (int z = 0; z < 200; z++) {
                float point = SnowPoints.point(x, z);
                int near = 0;
                while (near < shares.length && point < shares[near]) {
                    near++;
                }
                assertEquals(near, shaderLayers(shares, shaderPoint(x, z)));
            }
        }
    }
}
