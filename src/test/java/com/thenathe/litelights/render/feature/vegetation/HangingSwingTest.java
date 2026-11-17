package com.thenathe.litelights.render.feature.vegetation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * How hanging lanterns, chains and signs swing (HangingSwing, the Java twin of
 * shaders/include/vegetation/hanging.glsl).
 */
class HangingSwingTest {
    private static final Path SHADER = Path.of("src/main/resources/assets/litelights/shaders/include/vegetation/hanging.glsl");

    /** A lantern hanging under a block, at y 70. */
    private static int[] lantern(int x, int z, int chainBlocks) {
        int bottom = 70 * 16;
        int pivot = (71 + chainBlocks) * 16;
        int cycles = HangingSwing.cyclesPerHour(pivot - bottom, x, z);
        return new int[] {HangingSwing.packBottom(bottom, cycles), HangingSwing.packPivot(pivot, HangingSwing.FREE)};
    }

    private static float angle(int x, int z, int[] column, float seconds, float strength) {
        float[] tilt = new float[2];
        HangingSwing.tilt(x, z, column[0], column[1], seconds, 0.7f, strength, tilt);
        return (float) Math.hypot(tilt[0], tilt[1]);
    }

    @Test
    void packingKeepsNegativeHeightsAndTheModes() {
        int bottom = HangingSwing.packBottom(-63 * 16 - 1, 2471);
        int pivot = HangingSwing.packPivot(-60 * 16 + 15, 13);
        assertEquals(-63 * 16 - 1, HangingSwing.bottomSixteenths(bottom));
        assertEquals(2471, HangingSwing.cycles(bottom));
        assertEquals(-60 * 16 + 15, HangingSwing.pivotSixteenths(pivot));
        assertEquals(13, HangingSwing.mode(pivot));
        int high = HangingSwing.packPivot(2031 * 16, HangingSwing.FREE);
        assertEquals(2031 * 16, HangingSwing.pivotSixteenths(high));
    }

    @Test
    void longerColumnsSwingSlowerAndEveryRateIsWholeSwingsAnHour() {
        int shortest = HangingSwing.cyclesPerHour(16, 0, 0);
        int threeChains = HangingSwing.cyclesPerHour(4 * 16, 0, 0);
        int sixChains = HangingSwing.cyclesPerHour(7 * 16, 0, 0);
        assertTrue(shortest > threeChains && threeChains > sixChains, shortest + " " + threeChains + " " + sixChains);
        // About one and a half to two seconds a swing right under a block, five to seven on six chains.
        assertTrue(shortest > 1700 && shortest < 2600, "under a block: " + shortest);
        assertTrue(sixChains > 500 && sixChains < 800, "six chains: " + sixChains);
        for (int span = 1; span < 4096; span += 7) {
            int cycles = HangingSwing.cyclesPerHour(span, span * 31, -span);
            assertTrue(cycles >= 1 && cycles <= 4095, "span " + span + ": " + cycles);
        }
    }

    @Test
    void neighboursSwingOutOfStep() {
        int[] first = lantern(10, 4, 0);
        int[] second = lantern(11, 4, 0);
        int differing = 0;
        for (float seconds = 0.0f; seconds < 60.0f; seconds += 0.5f) {
            float[] a = new float[2];
            float[] b = new float[2];
            HangingSwing.tilt(10, 4, first[0], first[1], seconds, 0.0f, 0.5f, a);
            HangingSwing.tilt(11, 4, second[0], second[1], seconds, 0.0f, 0.5f, b);
            if (Math.abs(a[0] - b[0]) > 0.02f) {
                differing++;
            }
        }
        assertTrue(differing > 60, "out of step at " + differing + " of 120 moments");
    }

    @Test
    void theClocksWrapAtAnHourDoesNotShow() {
        for (int x = -300; x < 300; x += 37) {
            for (int chains = 0; chains < 8; chains += 3) {
                int[] column = lantern(x, 2 * x + 5, chains);
                float[] start = new float[2];
                float[] end = new float[2];
                HangingSwing.tilt(x, 2 * x + 5, column[0], column[1], 0.0f, 1.3f, 0.6f, start);
                HangingSwing.tilt(x, 2 * x + 5, column[0], column[1], 3600.0f, 1.3f, 0.6f, end);
                assertEquals(start[0], end[0], 2.0e-3f, "x " + x + ", " + chains + " chains");
                assertEquals(start[1], end[1], 2.0e-3f, "x " + x + ", " + chains + " chains");
            }
        }
    }

    @Test
    void theSwingIsSmoothFromFrameToFrame() {
        int[] column = lantern(3, 3, 0);
        float[] last = new float[2];
        HangingSwing.tilt(3, 3, column[0], column[1], 100.0f, 0.4f, 1.0f, last);
        for (float seconds = 100.0f; seconds < 130.0f; seconds += 1.0f / 60.0f) {
            float[] now = new float[2];
            HangingSwing.tilt(3, 3, column[0], column[1], seconds, 0.4f, 1.0f, now);
            assertTrue(Math.abs(now[0] - last[0]) < 0.02f && Math.abs(now[1] - last[1]) < 0.02f, "at " + seconds + " s");
            last = now;
        }
    }

    @Test
    void calmDaysBarelyMoveAndStormsSwingHarder() {
        int[] column = lantern(0, 0, 0);
        float calm = 0.0f;
        float storm = 0.0f;
        for (float seconds = 0.0f; seconds < 600.0f; seconds += 0.1f) {
            calm = Math.max(calm, angle(0, 0, column, seconds, 0.0f));
            storm = Math.max(storm, angle(0, 0, column, seconds, 1.0f));
        }
        // A lantern right under a block: about 3 degrees at most on a still day, up to about 20 in the strongest storm.
        assertTrue(calm > 0.02f && calm < 0.07f, "calm " + calm);
        assertTrue(storm > 0.2f && storm < 0.4f, "storm " + storm);
    }

    @Test
    void aLongChainSwingsThroughLessAngleButItsEndFurther() {
        int[] under = lantern(0, 0, 0);
        int[] sixChains = lantern(0, 0, 6);
        float shortAngle = 0.0f;
        float longAngle = 0.0f;
        for (float seconds = 0.0f; seconds < 600.0f; seconds += 0.1f) {
            shortAngle = Math.max(shortAngle, angle(0, 0, under, seconds, 1.0f));
            longAngle = Math.max(longAngle, angle(0, 0, sixChains, seconds, 1.0f));
        }
        assertTrue(longAngle < shortAngle * 0.5f, shortAngle + " against " + longAngle);
        float shortReach = shortAngle * HangingSwing.pendulumLength(16);
        float longReach = longAngle * HangingSwing.pendulumLength(7 * 16);
        // The lantern on six chains moves further, but well under a block in the strongest storm.
        assertTrue(longReach > shortReach && longReach < 0.8f, shortReach + " against " + longReach);
    }

    @Test
    void aSignHungFromABlockSwingsOnlyToAndFromItsFace() {
        for (int segment = 0; segment < 16; segment++) {
            int bottom = 70 * 16 - 1;
            int pivot = 71 * 16;
            int packedBottom = HangingSwing.packBottom(bottom, HangingSwing.cyclesPerHour(pivot - bottom, 5, 9));
            int packedPivot = HangingSwing.packPivot(pivot, 1 + segment);
            double faceAngle = segment * Math.PI / 8.0;
            float alongX = (float) Math.cos(faceAngle);
            float alongZ = (float) Math.sin(faceAngle);
            for (float seconds = 0.0f; seconds < 30.0f; seconds += 0.7f) {
                float[] tilt = new float[2];
                HangingSwing.tilt(5, 9, packedBottom, packedPivot, seconds, 2.1f, 0.8f, tilt);
                // Nothing along the board's width (the text's x axis turned by the segment).
                assertEquals(0.0f, tilt[0] * alongX + tilt[1] * alongZ, 1.0e-5f, "segment " + segment);
            }
        }
    }

    @Test
    void theTurnKeepsThePivotAndTheShape() {
        float tiltX = 0.2f;
        float tiltZ = -0.1f;
        float[] pivot = HangingSwing.turn(tiltX, tiltZ, 0.0f, 0.0f, 0.0f);
        assertEquals(0.0f, pivot[0], 1.0e-6f);
        assertEquals(0.0f, pivot[1], 1.0e-6f);
        assertEquals(0.0f, pivot[2], 1.0e-6f);
        // A point under the pivot swings toward the tilt, by the tilt's angle.
        float[] below = HangingSwing.turn(tiltX, tiltZ, 0.0f, -2.0f, 0.0f);
        double angle = Math.hypot(tiltX, tiltZ);
        assertEquals(2.0 * Math.sin(angle) * tiltX / angle, below[0], 1.0e-5);
        assertEquals(-2.0 * Math.cos(angle), below[1], 1.0e-5);
        assertEquals(2.0 * Math.sin(angle) * tiltZ / angle, below[2], 1.0e-5);
        // Two points of the same lantern keep their distance: the column turns as one piece.
        float[] a = HangingSwing.turn(tiltX, tiltZ, 0.3f, -0.4f, -0.2f);
        float[] b = HangingSwing.turn(tiltX, tiltZ, -0.1f, -0.9f, 0.25f);
        double before = Math.sqrt(0.4 * 0.4 + 0.5 * 0.5 + 0.45 * 0.45);
        double after = Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
        assertEquals(before, after, 1.0e-5);
    }

    @Test
    void theFadeMatchesThePlants() {
        assertEquals(1.0f, HangingSwing.fade(10.0f));
        assertEquals(1.0f, HangingSwing.fade(HangingSwing.FADE_START));
        assertEquals(0.5f, HangingSwing.fade(80.0f), 1.0e-6f);
        assertEquals(0.0f, HangingSwing.fade(HangingSwing.FADE_END));
        assertEquals(WindField.FADE_START, HangingSwing.FADE_START);
        assertEquals(WindField.FADE_END, HangingSwing.FADE_END);
    }

    @Test
    void theShaderUsesTheSameNumbers() throws IOException {
        String shader = Files.readString(SHADER);
        assertEquals(HangingSwing.MAX_COLUMNS, (int) constant(shader, "LITE_SWING_MAX_COLUMNS"));
        assertEquals(HangingSwing.FADE_START, constant(shader, "LITE_SWING_FADE_START"));
        assertEquals(HangingSwing.FADE_END, constant(shader, "LITE_SWING_FADE_END"));
        assertEquals(HangingSwing.CALM_SWING, constant(shader, "LITE_SWING_CALM"));
        assertEquals(HangingSwing.STORM_SWING, constant(shader, "LITE_SWING_STORM"));
        assertEquals(HangingSwing.LEAN, constant(shader, "LITE_SWING_LEAN"));
        assertEquals(HangingSwing.ACROSS, constant(shader, "LITE_SWING_ACROSS"));
        assertEquals(HangingSwing.SHORT_PENDULUM, constant(shader, "LITE_SWING_SHORT"));
        String frame = Files.readString(Path.of("src/main/resources/assets/litelights/shaders/include/frame.glsl"));
        assertTrue(frame.contains("ivec4 LiteSwingCells[" + HangingSwing.CELL_ROWS / 4 + "];"));
        assertTrue(frame.contains("ivec4 LiteSwingColumns[" + HangingSwing.MAX_COLUMNS + "];"));
    }

    private static float constant(String shader, String name) {
        Matcher matcher = Pattern.compile("const (?:int|float) " + name + " = ([0-9.]+);").matcher(shader);
        assertTrue(matcher.find(), name + " is in the shader");
        return Float.parseFloat(matcher.group(1));
    }
}
