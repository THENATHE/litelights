package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The far-terrain shaders' maths, mirrored in Java (shaders/include/farterrain/far_light.glsl and
 * farterrain/far_terrain.fsh), and the Java side's encodings. Keep the mirrors in step with the
 * shaders.
 */
class FarTerrainMathTest {
    /** lite_far_shadow_own: pixel - (pixel - air) * (1 - ratio), per channel. */
    private static float shadowOwn(float pixel, float air, float ratio) {
        return Math.max(pixel - (pixel - air) * (1.0f - ratio), 0.0f);
    }

    /** lite_fog: surface * (1 - hidden) + air colour * hidden, then the edge fade the same way. */
    private static float fogged(float surface, float airColour, float hidden, float edgeColour, float edge) {
        float hazed = surface * (1.0f - hidden) + airColour * hidden;
        return hazed * (1.0f - edge) + edgeColour * edge;
    }

    @Test
    void darkeningAFoggedPixelMatchesShadingTheSurfaceBeforeTheFog() {
        Random random = new Random(7);
        for (int i = 0; i < 10_000; i++) {
            float surface = random.nextFloat();
            float airColour = random.nextFloat();
            float hidden = random.nextFloat();
            float edgeColour = random.nextFloat();
            float edge = random.nextFloat();
            float ratio = 0.3f + 0.7f * random.nextFloat();
            float pixel = fogged(surface, airColour, hidden, edgeColour, edge);
            // lite_far_air_in_pixel: the air's weighted share of the pixel.
            float air = airColour * hidden * (1.0f - edge) + edgeColour * edge;
            float expected = fogged(surface * ratio, airColour, hidden, edgeColour, edge);
            assertEquals(expected, shadowOwn(pixel, air, ratio), 1.0e-5f);
        }
    }

    @Test
    void aFullyFoggedPixelKeepsItsColourUnderAFarShadow() {
        float air = 0.62f;
        assertEquals(air, shadowOwn(air, air, 0.4f), 1.0e-6f);
    }

    /** lite_far_shadow_at's weight for one half-resolution texel. */
    private static float upsampleWeight(float bilinear, float texelDistance, float pixelDistance) {
        return bilinear / (Math.abs(texelDistance - pixelDistance) + 0.02f * pixelDistance + 0.5f);
    }

    @Test
    void theUpsamplePrefersTexelsAtThePixelsOwnDistance() {
        float pixel = 900.0f;
        float sameSurface = upsampleWeight(0.25f, 905.0f, pixel);
        float hillInFront = upsampleWeight(0.25f, 300.0f, pixel);
        assertTrue(sameSurface > 20.0f * hillInFront, sameSurface + " vs " + hillInFront);
    }

    @Test
    void heightfieldTexelsHoldTheTopAboveTheFloorAndWrap() {
        assertEquals(0, FarHeightfield.encode(-64, -64));
        assertEquals(135, FarHeightfield.encode(71, -64));
        assertEquals(0, FarHeightfield.encode(-70, -64));
        assertEquals((short) 65535, FarHeightfield.encode(70_000, 0));
        assertEquals(FarHeightfield.texel(5), FarHeightfield.texel(5 + FarHeightfield.SIZE));
        assertEquals(FarHeightfield.SIZE - 1, FarHeightfield.texel(-1));
    }

    @Test
    void pushConstantsFollowTheShaderBlock() {
        FarValues values = new FarValues();
        values.shadowTexelX = 0.25f;
        values.shadowFadeStart = 230.4f;
        values.seaSurfaceHeight = -40.0f;
        values.heightfieldReach = 1008.0f;
        values.playerReach = 1.9f;
        ByteBuffer buffer = ByteBuffer.allocate(FarValues.BYTES).order(ByteOrder.nativeOrder());
        values.writeForTerrain(buffer);
        assertEquals(0.25f, buffer.getFloat(8));
        assertEquals(230.4f, buffer.getFloat(32));
        assertEquals(-40.0f, buffer.getFloat(40));
        values.writeForShadows(buffer);
        assertEquals(1008.0f, buffer.getFloat(20));
        assertEquals(1.9f, buffer.getFloat(60));
    }
}
