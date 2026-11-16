package com.thenathe.litelights.render.feature.vegetation;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sway wind must be one unbroken field: no seams at chunk or section borders, none where the
 * shader's wrapped world position or its clock wraps, and the same answer for a vertex shared by
 * two leaf blocks. These run on WindField, the Java twin of hooks/vegetation.glsl.
 */
class WindFieldTest {
    /** Any heading will do: within a frame it is one value for the whole world. */
    private static final float HEADING = 0.6f;
    /** Largest slope the wind may have, in offset units per block (about 1.1 for the waves used). */
    private static final float MAX_SLOPE = 2.5f;

    private static float[] plantAt(int cameraBlockX, int cameraBlockZ, double worldX, double worldZ, float seconds) {
        float x = WindField.wrap(cameraBlockX, (float) (worldX - cameraBlockX));
        float z = WindField.wrap(cameraBlockZ, (float) (worldZ - cameraBlockZ));
        return WindField.plant(x, z, seconds, HEADING);
    }

    private static float[] leavesAt(int[] cameraBlock, double[] world, float seconds) {
        float x = WindField.wrap(cameraBlock[0], (float) (world[0] - cameraBlock[0]));
        float y = WindField.wrap(cameraBlock[1], (float) (world[1] - cameraBlock[1]));
        float z = WindField.wrap(cameraBlock[2], (float) (world[2] - cameraBlock[2]));
        return WindField.leaves(x, y, z, seconds, HEADING);
    }

    private static float distance(float[] a, float[] b) {
        return (float) Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    @Test
    void noSeamAtChunkOrSectionBorders() {
        float step = 0.001f;
        for (int border = -64; border <= 64; border += 16) {
            for (float other = -40.5f; other < 40f; other += 3.7f) {
                for (float seconds : new float[] {0f, 17.3f, 1234.5f}) {
                    float[] beforeX = WindField.plant(border - step, other, seconds, HEADING);
                    float[] afterX = WindField.plant(border + step, other, seconds, HEADING);
                    assertTrue(distance(beforeX, afterX) < MAX_SLOPE * 2 * step, "plant seam at x = " + border);
                    float[] beforeY = WindField.leaves(other, border - step, other * 0.5f, seconds, HEADING);
                    float[] afterY = WindField.leaves(other, border + step, other * 0.5f, seconds, HEADING);
                    assertTrue(distance(beforeY, afterY) < MAX_SLOPE * 2 * step, "leaf seam at y = " + border);
                }
            }
        }
    }

    @Test
    void sameWindWhereverTheCameraIs() {
        // One world point, seen from camera blocks on both sides of several 256-block wraps.
        double worldX = 1000.3;
        double worldZ = -77.6;
        float[] reference = plantAt(1000, -77, worldX, worldZ, 42.0f);
        for (int cameraX = 900; cameraX <= 1100; cameraX += 7) {
            for (int cameraZ = -170; cameraZ <= 20; cameraZ += 11) {
                float[] seen = plantAt(cameraX, cameraZ, worldX, worldZ, 42.0f);
                assertTrue(distance(reference, seen) < 1.0e-4f, "camera at " + cameraX + ", " + cameraZ);
            }
        }
    }

    @Test
    void farFromTheOriginIsTheSameField() {
        // 29,999,872 is a multiple of 256, so this point wraps to the same place as (100.25, 30.75).
        double far = 29_999_872.0;
        float[] near = WindField.plant(100.25f, 30.75f, 99.0f, HEADING);
        float[] distant = plantAt(29_999_950, 29_999_900, far + 100.25, far + 30.75, 99.0f);
        assertTrue(distance(near, distant) < 1.0e-4f);
    }

    @Test
    void clockWrapIsSeamless() {
        // The shader's clock runs from 0 to 3600 s and starts again.
        for (float x = -50f; x < 50f; x += 6.1f) {
            float[] end = WindField.plant(x, x * 0.7f, WindField.PERIOD_SECONDS, HEADING);
            float[] start = WindField.plant(x, x * 0.7f, 0f, HEADING);
            assertTrue(distance(end, start) < 2.0e-3f, "plant wind jumps at the clock wrap, x = " + x);
            float[] leafEnd = WindField.leaves(x, 64f, x * 0.3f, WindField.PERIOD_SECONDS, HEADING);
            float[] leafStart = WindField.leaves(x, 64f, x * 0.3f, 0f, HEADING);
            assertTrue(distance(leafEnd, leafStart) < 2.0e-3f, "leaf wind jumps at the clock wrap, x = " + x);
        }
    }

    @Test
    void motionIsSmallAndSmoothFromFrameToFrame() {
        // At 60 frames a second, in a thunderstorm, the tip of a two-block plant (the most that
        // moves) must move well under a texture pixel (1/16 block) per frame: smooth, no popping.
        Random random = new Random(7);
        float frame = 1.0f / 60.0f;
        float strongest = WindField.PLANT_AMPLITUDE * 2.0f * WindField.weather(1.0f, 1.0f);
        float largestStep = 0;
        float largestOffset = 0;
        for (int i = 0; i < 20_000; i++) {
            float x = random.nextFloat() * 256f;
            float z = random.nextFloat() * 256f;
            float seconds = random.nextFloat() * 3600f;
            float[] now = WindField.plant(x, z, seconds, HEADING);
            float[] next = WindField.plant(x, z, seconds + frame, HEADING);
            largestStep = Math.max(largestStep, distance(now, next) * strongest);
            largestOffset = Math.max(largestOffset, distance(now, new float[3]) * strongest);
        }
        assertTrue(largestStep < 1.0f / 64.0f, "per-frame step " + largestStep);
        assertTrue(largestOffset < 0.35f, "largest offset " + largestOffset);
    }

    @Test
    void sharedLeafVertexMovesTheSameFromEitherBlock() {
        // A corner shared by two leaf blocks in different sections reaches the shader through
        // different float sums; its offset must still agree to far below a pixel.
        int[] camera = {1203, 71, -488};
        double[] corner = {1216.0, 80.0, -480.0};
        float fromFirst = (float) (corner[0] - camera[0]);
        float fromSecond = (float) ((corner[0] - 16.0 - camera[0]) + 16.0f);
        assertEquals(fromFirst, fromSecond, 1.0e-5f);
        float[] a = leavesAt(camera, corner, 10.0f);
        float[] b = WindField.leaves(WindField.wrap(camera[0], fromSecond), WindField.wrap(camera[1], (float) (corner[1] - camera[1])),
            WindField.wrap(camera[2], (float) (corner[2] - camera[2])), 10.0f, HEADING);
        assertTrue(distance(a, b) * WindField.LEAF_AMPLITUDE < 1.0e-6f);
    }

    @Test
    void feetStayOnTheGround() {
        // Plain feet, feet sunk by a random model offset, crops sunk into farmland, potted plants.
        for (float foot : new float[] {0.0f, -0.2f, -0.0625f, 0.25f}) {
            assertEquals(0.0f, WindField.plantHeight(5.0f + foot, false, false), "foot at " + foot);
        }
        assertEquals(1.0f, WindField.plantHeight(6.0f, true, false), 1.0e-6f);
        assertEquals(0.9375f, WindField.plantHeight(5.9375f, true, false), 1.0e-6f);
    }

    @Test
    void twoBlockPlantsStayJoined() {
        for (float offset : new float[] {0.0f, -0.1f, -0.2f}) {
            float join = 6.0f + offset;
            float lowerTop = WindField.plantHeight(join, true, false);
            float upperFoot = WindField.plantHeight(join, false, true);
            assertEquals(lowerTop, upperFoot, 1.0e-6f, "offset " + offset);
            assertEquals(lowerTop + 1.0f, WindField.plantHeight(join + 1.0f, true, true), 1.0e-6f);
        }
    }

    @Test
    void fadesOutWithDistance() {
        assertEquals(1.0f, WindField.fade(10.0f));
        assertEquals(0.0f, WindField.fade(WindField.FADE_END));
        float previous = 1.0f;
        for (float distance = WindField.FADE_START; distance <= WindField.FADE_END; distance += 1.0f) {
            float fade = WindField.fade(distance);
            assertTrue(fade <= previous && previous - fade < 0.06f);
            previous = fade;
        }
    }
}
