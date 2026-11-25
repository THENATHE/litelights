package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * What the field costs on one thread: a chunk of 32 by 32 columns near the camera (8-block cubes,
 * 256 blocks square) and far away (64-block cubes, 2,048 blocks square), and making the sky. The
 * numbers are printed for the record; the test fails only if a near chunk takes far longer than
 * the design allows (docs/CLOUDS-FIELD.md, "Cost").
 */
class CloudFieldCostTest {
    private static final int CHUNK = 32;
    private static final int CHUNKS = 24;
    private static final int ROUNDS = 3;
    /** A near chunk must not take longer than this, in milliseconds: a generous ceiling, not the goal. */
    private static final double MOST_NEAR_CHUNK_MS = 20.0;

    @Test
    void costPerChunkOfColumns() {
        double worstNearMedian = 0.0;
        for (CloudFieldPictures.Weather weather : CloudFieldPictures.Weather.values()) {
            Timing near = null;
            Timing far = null;
            for (int round = 0; round < ROUNDS; round++) {
                CloudField field = TestSkies.vanilla(TestSkies.TICK, weather.rain, weather.thunder);
                near = timeChunks(field, 8.0, 200.0, 1_000.0);
                far = timeChunks(field, 64.0, 4_000.0, 7_000.0);
            }
            double skyMs = timeSky(weather.rain, weather.thunder);
            // A whole rebuild out to 8 km, in rings of doubling cube size from 1 km (as the clouds writeup lays out).
            double nearColumns = Math.PI * 1_000.0 * 1_000.0 / 64.0;
            double middleColumns = Math.PI * (2_000.0 * 2_000.0 - 1_000.0 * 1_000.0) / 256.0
                + Math.PI * (4_000.0 * 4_000.0 - 2_000.0 * 2_000.0) / 1_024.0;
            double farColumns = Math.PI * (8_000.0 * 8_000.0 - 4_000.0 * 4_000.0) / 4_096.0;
            double rebuildMs = (nearColumns + middleColumns) * near.perColumnMicros / 1_000.0 + farColumns * far.perColumnMicros / 1_000.0;
            System.out.println(String.format(Locale.ROOT,
                "cloud field cost, %s, one thread: near chunk (32x32 columns at 8 blocks) median %.2f ms, slowest %.2f ms (%.2f us a column); "
                    + "far chunk (32x32 at 64 blocks) median %.2f ms, slowest %.2f ms (%.2f us a column); making the sky %.2f ms; "
                    + "a whole rebuild to 8 km (about %.0f columns) about %.0f ms",
                weather.label, near.medianMs, near.slowestMs, near.perColumnMicros, far.medianMs, far.slowestMs, far.perColumnMicros,
                skyMs, nearColumns + middleColumns + farColumns, rebuildMs));
            worstNearMedian = Math.max(worstNearMedian, near.medianMs);
        }
        assertTrue(worstNearMedian < MOST_NEAR_CHUNK_MS, "a near chunk took " + worstNearMedian + " ms");
    }

    private record Timing(double medianMs, double slowestMs, double perColumnMicros) {
    }

    /** Times {@link #CHUNKS} chunks spread round the camera between two distances, each with its own area. */
    private static Timing timeChunks(CloudField field, double cubeSize, double nearest, double farthest) {
        double tick = TestSkies.TICK;
        double chunkBlocks = CHUNK * cubeSize;
        double[] millis = new double[CHUNKS];
        CloudColumn column = new CloudColumn();
        for (int chunk = 0; chunk < CHUNKS; chunk++) {
            double angle = chunk * 2.399963;
            double distance = nearest + (farthest - nearest) * ((chunk * 0.618034) % 1.0);
            double minX = Math.cos(angle) * distance - field.driftX(tick);
            double minZ = Math.sin(angle) * distance - field.driftZ(tick);
            long start = System.nanoTime();
            CloudField.Area area = field.area(tick, minX, minZ, minX + chunkBlocks, minZ + chunkBlocks);
            for (int z = 0; z < CHUNK; z++) {
                for (int x = 0; x < CHUNK; x++) {
                    area.column(minX + (x + 0.5) * cubeSize, minZ + (z + 0.5) * cubeSize, cubeSize, column);
                }
            }
            millis[chunk] = (System.nanoTime() - start) / 1.0e6;
        }
        double[] sorted = millis.clone();
        Arrays.sort(sorted);
        double median = sorted[CHUNKS / 2];
        return new Timing(median, sorted[CHUNKS - 1], median * 1_000.0 / (CHUNK * CHUNK));
    }

    private static double timeSky(float rain, float thunder) {
        double[] millis = new double[20];
        for (int i = 0; i < millis.length; i++) {
            long start = System.nanoTime();
            VanillaWeatherSky.sky(TestSkies.SEED, TestSkies.BASE, TestSkies.TICK + i * 20.0, rain, thunder, 0.0, 0.0, TestSkies.REACH);
            millis[i] = (System.nanoTime() - start) / 1.0e6;
        }
        Arrays.sort(millis);
        return millis[millis.length / 2];
    }
}
