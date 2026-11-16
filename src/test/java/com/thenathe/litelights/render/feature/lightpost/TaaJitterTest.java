package com.thenathe.litelights.render.feature.lightpost;

import com.thenathe.litelights.render.frame.WorldProjection;
import java.util.HashSet;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaaJitterTest {
    @Test
    void everyPointStaysInsideItsPixelAndNoneRepeatsWithinACycle() {
        Set<String> seen = new HashSet<>();
        for (int frame = 0; frame < TaaJitter.STEPS; frame++) {
            float x = TaaJitter.x(frame);
            float y = TaaJitter.y(frame);
            assertTrue(x > -0.5f && x < 0.5f && y > -0.5f && y < 0.5f, "frame " + frame + " at " + x + ", " + y);
            assertTrue(seen.add(x + "," + y), "frame " + frame + " repeats an earlier point");
        }
    }

    @Test
    void theCycleAveragesToThePixelCentre() {
        float sumX = 0.0f;
        float sumY = 0.0f;
        for (int frame = 0; frame < TaaJitter.STEPS; frame++) {
            sumX += TaaJitter.x(frame);
            sumY += TaaJitter.y(frame);
        }
        assertEquals(0.0f, sumX / TaaJitter.STEPS, 1.0e-6f);
        assertEquals(0.0f, sumY / TaaJitter.STEPS, 1.0e-6f);
    }

    @Test
    void eachQuarterOfThePixelGetsTwoOfTheEightPointsAcross() {
        int[] quarters = new int[4];
        float shift = TaaJitter.x(0) - (TaaJitter.halton(1, 2) - 0.5f);
        for (int frame = 0; frame < TaaJitter.STEPS; frame++) {
            float raw = TaaJitter.x(frame) - shift + 0.5f;
            quarters[Math.min(3, (int) (raw * 4.0f))]++;
        }
        for (int count : quarters) {
            assertEquals(2, count);
        }
    }

    @Test
    void theSequenceRepeatsEveryCycleAndAcceptsAnyFrameNumber() {
        for (int frame = -20; frame < 20; frame++) {
            assertEquals(TaaJitter.x(frame), TaaJitter.x(frame + TaaJitter.STEPS));
            assertEquals(TaaJitter.y(frame), TaaJitter.y(frame + TaaJitter.STEPS));
        }
    }

    @Test
    void haltonPointsAreTheRadicalInverse() {
        assertEquals(0.5f, TaaJitter.halton(1, 2), 1.0e-6f);
        assertEquals(0.25f, TaaJitter.halton(2, 2), 1.0e-6f);
        assertEquals(0.75f, TaaJitter.halton(3, 2), 1.0e-6f);
        assertEquals(1.0f / 3.0f, TaaJitter.halton(1, 3), 1.0e-6f);
        assertEquals(1.0f / 9.0f, TaaJitter.halton(3, 3), 1.0e-6f);
    }

    @Test
    void theProjectionSeamMovesThePictureByTheJitterInPixels() {
        int width = 1920;
        int height = 1080;
        Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 1024.0f, 0.05f, true);
        WorldProjection seam = new WorldProjection();
        Vector4f point = new Vector4f(3.0f, -1.5f, -20.0f, 1.0f);
        Vector4f still = seam.adjust(projection).transform(new Vector4f(point));
        seam.setJitter(TaaJitter.x(3), TaaJitter.y(3), width, height);
        Vector4f jittered = seam.adjust(projection).transform(new Vector4f(point));
        float movedX = (jittered.x / jittered.w - still.x / still.w) * 0.5f * width;
        float movedY = (jittered.y / jittered.w - still.y / still.w) * 0.5f * height;
        assertEquals(TaaJitter.x(3), movedX, 1.0e-3f);
        assertEquals(TaaJitter.y(3), movedY, 1.0e-3f);
        // The matrices the reprojection is built from stay unjittered.
        assertEquals(projection, seam.unjittered());
    }
}
