package com.thenathe.litelights.render.feature.lightpost;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TAA while the camera moves (taa.fsh, through TaaMirror): the new frame's weight and the history's sharpen. */
class TaaMotionTest {
    private static final float STILL = 0.1f;
    private static final float TRANSLUCENT = 0.2f;
    private static final float MOVING = 0.3f;
    private static final float MOVING_PIXELS = 6.0f;
    private static final float SHARPEN = 0.9f;

    @Test
    void aStillCameraKeepsTheLongHistory() {
        assertEquals(STILL, TaaMirror.newFrameWeight(STILL, MOVING, MOVING_PIXELS, 0.0f), 1.0e-6f);
        assertEquals(STILL, TaaMirror.newFrameWeight(STILL, MOVING, MOVING_PIXELS, 1.0f), 1.0e-6f);
    }

    @Test
    void aFastCameraTakesMoreOfTheNewFrame() {
        float slow = TaaMirror.newFrameWeight(STILL, MOVING, MOVING_PIXELS, 3.0f);
        assertTrue(slow > STILL && slow < MOVING, "halfway weight " + slow);
        assertEquals(MOVING, TaaMirror.newFrameWeight(STILL, MOVING, MOVING_PIXELS, 6.0f), 1.0e-6f);
        assertEquals(MOVING, TaaMirror.newFrameWeight(STILL, MOVING, MOVING_PIXELS, 40.0f), 1.0e-6f);
    }

    @Test
    void behindTranslucentSurfacesTheWeightNeverDrops() {
        for (float movement = 0.0f; movement < 10.0f; movement += 0.5f) {
            assertTrue(TaaMirror.newFrameWeight(TRANSLUCENT, MOVING, MOVING_PIXELS, movement) >= TRANSLUCENT);
        }
    }

    @Test
    void wholePixelStepsAreNotSharpened() {
        float[] row = {0.1f, 0.9f, 0.2f, 0.8f, 0.3f, 0.7f, 0.1f, 0.9f};
        for (int step = 1; step <= 3; step++) {
            float position = 3.5f + step;
            float catmullRom = TaaMirror.catmullRom(row, position);
            float bilinear = TaaMirror.bilinear(row, position);
            assertEquals(row[3 + step], catmullRom, 1.0e-6f);
            assertEquals(catmullRom, TaaMirror.sharpenedHistory(catmullRom, bilinear, SHARPEN, step), 1.0e-6f);
        }
    }

    @Test
    void aStillHistoryIsNotSharpened() {
        assertEquals(0.4f, TaaMirror.sharpenedHistory(0.4f, 0.3f, SHARPEN, 0.0f), 1.0e-6f);
        assertEquals(0.4f, TaaMirror.sharpenedHistory(0.4f, 0.3f, SHARPEN, 0.04f), 1.0e-6f);
    }

    @Test
    void sharpeningKeepsDetailThatRepeatedReadsBetweenPixelsWouldLose() {
        // A row of alternating texels moved a fifth of a pixel a frame, read again each frame and
        // blended with nothing new: without the sharpen the pattern fades much faster.
        float[] plain = new float[32];
        float[] sharpened = new float[32];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = i % 2 == 0 ? 0.2f : 0.8f;
            sharpened[i] = plain[i];
        }
        float step = 0.2f;
        for (int frame = 0; frame < 5; frame++) {
            float[] nextPlain = new float[plain.length];
            float[] nextSharpened = new float[plain.length];
            for (int i = 0; i < plain.length; i++) {
                float position = i + 0.5f + step;
                nextPlain[i] = TaaMirror.catmullRom(plain, position);
                float catmullRom = TaaMirror.catmullRom(sharpened, position);
                nextSharpened[i] = TaaMirror.sharpenedHistory(catmullRom, TaaMirror.bilinear(sharpened, position), SHARPEN, step);
            }
            plain = nextPlain;
            sharpened = nextSharpened;
        }
        float plainContrast = Math.abs(plain[16] - plain[15]);
        float sharpenedContrast = Math.abs(sharpened[16] - sharpened[15]);
        assertTrue(sharpenedContrast > plainContrast * 1.3f, "contrast " + sharpenedContrast + " against " + plainContrast);
        assertTrue(sharpenedContrast <= 0.6f + 1.0e-3f, "no overshoot past the original contrast: " + sharpenedContrast);
    }
}
