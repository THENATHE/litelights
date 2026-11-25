package com.thenathe.litelights.render.feature.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Deep water under Improved Transparency (fix18): the alpha every OIT stage gives a water top face
 * lets the colour stage reach the classic look over any bottom, where the texture's own alpha left
 * the bottom showing through as a purple tint.
 */
class WaterCoverageTest {
    /** Ocean water's biome colour. */
    private static final float[] HUE = {0.247f, 0.463f, 0.894f};
    private static final float[][] BOTTOMS = {
        {0.86f, 0.80f, 0.62f},  // sand in full light
        {0.50f, 0.47f, 0.36f},  // sand under a few blocks of water
        {0.45f, 0.32f, 0.22f},  // dirt
        {0.50f, 0.50f, 0.50f},  // stone
        {1.0f, 1.0f, 1.0f},     // white concrete
        {0.05f, 0.05f, 0.05f},  // deep and dark
    };
    private static final float LEVEL = 1.0f / 255.0f;

    @Test
    void theTextureAlphaLeftASandBottomShowingThroughInRed() {
        float[] bottom = BOTTOMS[1];
        float[] body = body(1.0f);
        float[] seen = WaterCoverageMirror.seen(bottom, body, HUE, 1.0f, true, WaterCoverageMirror.TEXTURE_ALPHA, 6.0f);
        float[] screen = WaterCoverageMirror.improvedTransparency(seen, bottom, WaterCoverageMirror.TEXTURE_ALPHA);
        assertTrue(screen[0] - seen[0] > 3 * LEVEL, "red " + screen[0] + " against " + seen[0]);
        assertEquals(seen[1], screen[1], LEVEL);
        assertEquals(seen[2], screen[2], LEVEL);
    }

    @Test
    void theCoverageAlphaGivesTheClassicLookOverEveryBottom() {
        for (boolean realistic : new boolean[] {false, true}) {
            for (float light : new float[] {0.15f, 0.6f, 1.0f}) {
                for (float[] lit : BOTTOMS) {
                    float[] bottom = scaled(lit, light);
                    for (float thickness = 0.0f; thickness <= 64.0f; thickness += 0.25f) {
                        float alpha = WaterCoverageMirror.TEXTURE_ALPHA;
                        float[] seen = WaterCoverageMirror.seen(bottom, body(light), HUE, light, realistic, alpha, thickness);
                        float coverage = WaterCoverageMirror.coverage(alpha, thickness, realistic, true);
                        float[] screen = WaterCoverageMirror.improvedTransparency(seen, bottom, coverage);
                        for (int i = 0; i < 3; i++) {
                            assertEquals(seen[i], screen[i], 1.0e-4f,
                                "realistic " + realistic + ", light " + light + ", thickness " + thickness + ", channel " + i);
                        }
                    }
                }
            }
        }
    }

    @Test
    void coverageOnlyEverRaisesTheAlphaAndNeverReachesOpaque() {
        float alpha = WaterCoverageMirror.TEXTURE_ALPHA;
        for (boolean realistic : new boolean[] {false, true}) {
            for (float thickness = 0.0f; thickness <= 64.0f; thickness += 0.5f) {
                float coverage = WaterCoverageMirror.coverage(alpha, thickness, realistic, true);
                assertTrue(coverage >= alpha);
                // Vanilla's depth-bounds stage treats an alpha above 0.99 as opaque.
                assertTrue(coverage <= 0.96f + 1.0e-6f);
            }
        }
        assertEquals(alpha, WaterCoverageMirror.coverage(alpha, 0.0f, false, true), 1.0e-6f);
        assertEquals(alpha, WaterCoverageMirror.coverage(alpha, 30.0f, false, false), 1.0e-6f);
        assertEquals(alpha, WaterCoverageMirror.coverage(alpha, 1.0f, true, true), 1.0e-6f);
    }

    private static float[] body(float light) {
        return scaled(HUE, 0.75f * light);
    }

    private static float[] scaled(float[] colour, float by) {
        return new float[] {colour[0] * by, colour[1] * by, colour[2] * by};
    }
}
