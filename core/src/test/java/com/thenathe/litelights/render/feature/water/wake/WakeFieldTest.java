package com.thenathe.litelights.render.feature.water.wake;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The wakes' simulation (WakeField), its sources (WakeShapes) and the shader's reading of it (WakeLook). */
class WakeFieldTest {
    private static final int ORIGIN = -32;
    private static final int CELLS = WakeField.CELLS_PER_BLOCK;

    private static WakeField openWater() {
        WakeField field = new WakeField();
        field.moveWindow(ORIGIN, ORIGIN);
        field.fillWater(true);
        return field;
    }

    /** A boat at full speed from (-20, 0) toward +x for the given ticks; returns where it ended. */
    private static double sail(WakeField field, int ticks) {
        WakeSourceList sources = new WakeSourceList();
        double x = -20.0;
        for (int tick = 0; tick < ticks; tick++) {
            sources.clear();
            WakeShapes.boat(sources, x, 0.0, x + 0.4, 0.0, 1.0, 0.0, 0.4, 1.0);
            field.step(sources);
            x += 0.4;
        }
        return x;
    }

    private static void ring(WakeField field, double x, double z) {
        WakeSourceList sources = new WakeSourceList();
        WakeShapes.entry(sources, x, z, 0.6, 0.6, 1.0);
        field.step(sources);
    }

    private static void quiet(WakeField field, int ticks) {
        WakeSourceList none = new WakeSourceList();
        for (int tick = 0; tick < ticks; tick++) {
            field.step(none);
        }
    }

    private static int changedTiles(WakeField field) {
        int changed = 0;
        for (int tile = 0; tile < WakeField.TILES * WakeField.TILES; tile++) {
            if (field.tileChanged(tile)) {
                changed++;
            }
        }
        return changed;
    }

    @Test
    void theSameSourcesGiveTheSameTexels() {
        WakeField first = openWater();
        WakeField second = openWater();
        sail(first, 70);
        sail(second, 70);
        ring(first, 3.0, -5.0);
        ring(second, 3.0, -5.0);
        first.moveWindow(ORIGIN + 3, ORIGIN - 2);
        second.moveWindow(ORIGIN + 3, ORIGIN - 2);
        quiet(first, 15);
        quiet(second, 15);
        assertArrayEquals(first.texels(), second.texels());
    }

    @Test
    void calmWaterCostsNothingAndUploadsNothing() {
        WakeField field = openWater();
        quiet(field, 1);
        field.markUploaded();
        quiet(field, 5);
        assertEquals(0, field.lastWorkCells());
        assertEquals(0, changedTiles(field));
        assertTrue(field.isFlat());
        for (int texel : field.texels()) {
            assertEquals(WakeField.FLAT_TEXEL, texel);
        }
    }

    @Test
    void aRingSpreadsAtTheWaveSpeedAndDiesAway() {
        WakeField field = openWater();
        ring(field, 0.0, 0.0);
        quiet(field, 39);
        // 0.6 cells a tick: the front is about 24 cells (6 blocks) out after 40 ticks.
        int front = 0;
        for (int cell = 0; cell < 60; cell++) {
            if (Math.abs(field.heightAt(cell, 0)) > 0.01f) {
                front = cell;
            }
        }
        assertTrue(front >= 18 && front <= 32, "front at " + front + " cells");
        assertEquals(field.heightAt(front, 0), field.heightAt(0, front), 1.0e-6f, "round");
        quiet(field, 600);
        assertEquals(0.0f, maxHeight(field), "the ring has died away");
        field.markUploaded();
        quiet(field, 1);
        assertEquals(0, field.lastWorkCells(), "the waves are gone and nothing is worked on");
    }

    @Test
    void aFastBoatLeavesAVeeOfAboutTwentyDegrees() {
        WakeField field = openWater();
        double boatX = sail(field, 80);
        // Twelve blocks behind the boat, the wake's arms are where the water moves most across the track.
        int behind = (int) Math.round((boatX - 12.0) * CELLS);
        int strongest = 0;
        float most = 0.0f;
        for (int cell = 6; cell < 60; cell++) {
            float amount = Math.abs(field.heightAt(behind, cell));
            if (amount > most) {
                most = amount;
                strongest = cell;
            }
        }
        double halfAngle = Math.toDegrees(Math.atan2(strongest / (double) CELLS, 12.0));
        assertTrue(halfAngle > 12.0 && halfAngle < 32.0, "half angle " + halfAngle);
        // Symmetric about the track.
        assertEquals(field.heightAt(behind, strongest), field.heightAt(behind, -strongest - 1), 0.02f);
        // Foam trails behind it.
        assertTrue(field.foamAt((int) Math.round((boatX - 2.0) * CELLS), 0) > 0.1f);
    }

    @Test
    void wavesStopAtTheShore() {
        WakeField field = openWater();
        // A wall of land two blocks wide at x 0 and 1: a pool on each side.
        for (int z = 0; z < WakeField.BLOCKS; z++) {
            field.setWater(32, z, false);
            field.setWater(33, z, false);
        }
        ring(field, -6.0, 0.0);
        quiet(field, 120);
        float acrossTheWall = 0.0f;
        for (int x = 2 * CELLS; x < 20 * CELLS; x++) {
            for (int z = -20 * CELLS; z < 20 * CELLS; z++) {
                acrossTheWall = Math.max(acrossTheWall, Math.abs(field.heightAt(x, z)));
            }
        }
        assertEquals(0.0f, acrossTheWall, "nothing crosses the land");
        assertEquals(0.0f, field.heightAt(CELLS / 2, 0), "the land itself stays flat");
        assertTrue(maxHeight(field) > 0.0f, "the pool with the ring has waves");
    }

    @Test
    void nothingComesBackAcrossTheWindowsEdge() {
        WakeField field = openWater();
        ring(field, ORIGIN + 1.5, 0.0);
        quiet(field, 200);
        float farSide = 0.0f;
        for (int x = (ORIGIN + WakeField.BLOCKS - 10) * CELLS; x < (ORIGIN + WakeField.BLOCKS) * CELLS; x++) {
            farSide = Math.max(farSide, Math.abs(field.heightAt(x, 0)));
        }
        assertEquals(0.0f, farSide);
    }

    @Test
    void movingTheWindowKeepsTheWakeAndClearsWhatComesIn() {
        WakeField field = openWater();
        sail(field, 40);
        int keptX = -8 * CELLS;
        float before = field.heightAt(keptX, 6);
        float foamBefore = field.foamAt(keptX, 0);
        field.markUploaded();
        field.moveWindow(ORIGIN + 5, ORIGIN);
        assertEquals(before, field.heightAt(keptX, 6), "the same world cell keeps its water");
        assertEquals(foamBefore, field.foamAt(keptX, 0));
        // The columns that came in on the east side hold nothing from the west edge they replaced.
        for (int x = (ORIGIN + WakeField.BLOCKS) * CELLS; x < (ORIGIN + 5 + WakeField.BLOCKS) * CELLS; x++) {
            for (int z = ORIGIN * CELLS; z < (ORIGIN + WakeField.BLOCKS) * CELLS; z++) {
                assertEquals(0.0f, field.heightAt(x, z));
            }
        }
        field.moveWindow(ORIGIN + 500, ORIGIN);
        assertEquals(0.0f, maxHeight(field), "a jump clears everything");
    }

    @Test
    void onlyTilesWithWavesAreUploaded() {
        WakeField field = openWater();
        quiet(field, 1);
        field.markUploaded();
        ring(field, 0.5, 0.5);
        int changed = changedTiles(field);
        assertTrue(changed >= 1 && changed <= 4, changed + " tiles");
        field.markUploaded();
        quiet(field, 1000);
        // The last tiles go up once more, flat, then nothing.
        field.markUploaded();
        quiet(field, 2);
        assertEquals(0, changedTiles(field));
        assertTrue(field.isFlat());
    }

    @Test
    void aMinuteOfCirclingBoatsStaysCalm() {
        WakeField field = openWater();
        WakeSourceList sources = new WakeSourceList();
        double angle = 0.0;
        for (int tick = 0; tick < 1200; tick++) {
            sources.clear();
            for (int boat = 0; boat < 4; boat++) {
                double radius = 6.0 + boat * 3.0;
                double a = angle * (boat % 2 == 0 ? 1.0 : -1.0) + boat;
                double next = a + (boat % 2 == 0 ? 0.04 : -0.04);
                WakeShapes.boat(sources, Math.cos(a) * radius, Math.sin(a) * radius, Math.cos(next) * radius, Math.sin(next) * radius,
                    -Math.sin(next), Math.cos(next), 0.04 * radius, 2.0);
            }
            field.step(sources);
            angle += 0.04;
        }
        float most = maxHeight(field);
        assertFalse(Float.isNaN(most));
        assertTrue(most < 3.0f, "most " + most);
    }

    @Test
    void slowOrGentleThingsLeaveNothing() {
        WakeSourceList sources = new WakeSourceList();
        WakeShapes.boat(sources, 0.0, 0.0, 0.01, 0.0, 1.0, 0.0, 0.01, 1.0);
        WakeShapes.swimmer(sources, 0.0, 0.0, 0.01, 0.0, 1.0, 0.0, 0.01, 0.6, 1.0);
        WakeShapes.entry(sources, 0.0, 0.0, 0.6, 0.05, 1.0);
        assertEquals(0, sources.size());
        WakeShapes.boat(sources, 0.0, 0.0, 0.3, 0.0, 1.0, 0.0, 0.3, 1.0);
        assertEquals(1, sources.size());
    }

    @Test
    void wakeStrengthScalesTheWaves() {
        WakeSourceList weak = new WakeSourceList();
        WakeSourceList strong = new WakeSourceList();
        WakeShapes.boat(weak, 0.0, 0.0, 0.3, 0.0, 1.0, 0.0, 0.3, 0.5);
        WakeShapes.boat(strong, 0.0, 0.0, 0.3, 0.0, 1.0, 0.0, 0.3, 1.0);
        assertEquals(strong.depth(0) * 0.5, weak.depth(0), 1.0e-9);
    }

    @Test
    void theSourceListHoldsAFixedNumber() {
        WakeSourceList sources = new WakeSourceList();
        for (int i = 0; i < WakeSourceList.CAPACITY + 10; i++) {
            sources.addRing(i, 0.0, 0.5, 0.5, 0.5);
        }
        assertEquals(WakeSourceList.CAPACITY, sources.size());
        assertTrue(sources.isFull());
    }

    @Test
    void texelsPackHeightAndFoam() {
        assertEquals(WakeField.FLAT_TEXEL, WakeField.texel(0.0f, 0.0f, 0.0f, 0.0f));
        int texel = WakeField.texel(0.5f, -0.25f, 1.0f, 0.5f);
        assertEquals(128 + 48, texel & 255);
        assertEquals(128 - 24, texel >> 8 & 255);
        assertEquals(255, texel >> 16 & 255);
        assertEquals(128, texel >>> 24);
        int clamped = WakeField.texel(9.0f, -9.0f, 2.0f, -1.0f);
        assertEquals(255, clamped & 255);
        assertEquals(0, clamped >> 8 & 255);
        assertEquals(255, clamped >> 16 & 255);
        assertEquals(0, clamped >>> 24);
    }

    @Test
    void theShaderReadsFlatWaterAsUntouched() {
        WakeField field = openWater();
        quiet(field, 1);
        double[] wake = WakeLook.sample(field, 0.3, 0.7, 0.5);
        assertEquals(0.0, wake[0]);
        assertEquals(0.0, wake[1]);
        assertEquals(0.0, wake[2]);
        assertEquals(0.0, wake[3]);
        int calm = WakeLook.colour(field, 0.3, 0.7, 16);
        assertEquals(calm, WakeLook.colour(field, 5.3, -2.7, 16), "flat water is one colour everywhere");
    }

    @Test
    void theShaderBlendsTicksAndFindsTheSlope() {
        WakeField field = openWater();
        ring(field, 0.0, 0.0);
        quiet(field, 10);
        // Between two cells the blend lies between their heights; the slope points up the rise.
        double[] here = WakeLook.sample(field, 1.0, 0.13, 1.0);
        double[] left = WakeLook.sample(field, 1.0 - 0.01, 0.13, 1.0);
        double[] right = WakeLook.sample(field, 1.0 + 0.01, 0.13, 1.0);
        double rise = (right[0] - left[0]) / 0.02;
        assertEquals(rise, here[1], Math.abs(rise) * 0.2 + 1.0e-3);
        double[] before = WakeLook.sample(field, 1.0, 0.13, 0.0);
        double[] halfway = WakeLook.sample(field, 1.0, 0.13, 0.5);
        assertEquals((before[0] + here[0]) / 2.0, halfway[0], 1.0e-9);
    }

    private static float maxHeight(WakeField field) {
        int fromX = field.originBlockX() * CELLS;
        int fromZ = field.originBlockZ() * CELLS;
        float most = 0.0f;
        for (int z = fromZ; z < fromZ + WakeField.SIZE; z++) {
            for (int x = fromX; x < fromX + WakeField.SIZE; x++) {
                most = Math.max(most, Math.abs(field.heightAt(x, z)));
            }
        }
        return most;
    }
    /** Foam cells over a quarter and the summed and largest wave height after 80 ticks at a steady speed. */
    private static double[] wakeAfter(boolean boat, double speed) {
        WakeField field = new WakeField();
        field.moveWindow(-32, -32);
        field.fillWater(true);
        WakeSourceList sources = new WakeSourceList();
        double x = -20.0;
        for (int tick = 0; tick < 80; tick++) {
            sources.clear();
            if (boat) {
                WakeShapes.boat(sources, x, 0.0, x + speed, 0.0, 1.0, 0.0, speed, 1.0);
            } else {
                WakeShapes.swimmer(sources, x, 0.0, x + speed, 0.0, 1.0, 0.0, speed, 0.6, 1.0);
            }
            field.step(sources);
            x += speed;
        }
        double foamCells = 0.0;
        double heights = 0.0;
        double highest = 0.0;
        for (int cellZ = -128; cellZ < 128; cellZ++) {
            for (int cellX = -128; cellX < 128; cellX++) {
                foamCells += field.foamAt(cellX, cellZ) > 0.25f ? 1.0 : 0.0;
                heights += Math.abs(field.heightAt(cellX, cellZ));
                highest = Math.max(highest, Math.abs(field.heightAt(cellX, cellZ)));
            }
        }
        return new double[] {foamCells, heights, highest};
    }

    @Test
    void aSwimmerAtTheSurfaceLeavesAClearWakeThatStaysUnderABoats() {
        double[] surfaceSwim = wakeAfter(false, 0.1);
        double[] sprintSwim = wakeAfter(false, 0.25);
        double[] slowBoat = wakeAfter(true, 0.15);
        double[] boatAtSprint = wakeAfter(true, 0.25);
        assertTrue(surfaceSwim[0] > 30.0, "foam behind a surface swim: " + surfaceSwim[0]);
        assertTrue(surfaceSwim[2] > 0.08, "crests a surface swim throws up: " + surfaceSwim[2]);
        assertTrue(surfaceSwim[0] < slowBoat[0] && surfaceSwim[1] < slowBoat[1], "smaller than a slow boat's");
        assertTrue(sprintSwim[0] < boatAtSprint[0] * 0.6 && sprintSwim[1] < boatAtSprint[1] * 0.6,
            "a sprint swim's well under a boat's at the same speed");
    }
}
