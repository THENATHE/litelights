package com.thenathe.litelights.render.feature.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The snow fans in under trees and overhangs with a steady, round falloff, and never through walls. */
class SnowFanFieldTest {
    private static final int SIZE = 32;

    private static byte[] fanOf(byte[] kinds, int[] heights) {
        byte[] fan = new byte[SIZE * SIZE];
        new SnowFanField(SIZE).compute(kinds, heights, fan);
        return fan;
    }

    private static int at(byte[] fan, int x, int z) {
        return fan[z * SIZE + x] & 0xFF;
    }

    /** Open snow everywhere but a round canopy of covered ground, radius 6, around (16, 16). */
    private static byte[] roundCanopy(byte kind) {
        byte[] kinds = new byte[SIZE * SIZE];
        Arrays.fill(kinds, SnowFanField.SOURCE);
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                if (Math.hypot(x - 16, z - 16) <= 6.0) {
                    kinds[z * SIZE + x] = kind;
                }
            }
        }
        return kinds;
    }

    @Test
    void underATreeTheSnowFallsOffSteadilyInwardFromTheEdge() {
        byte[] fan = fanOf(roundCanopy(SnowFanField.UNDER_TREE), new int[SIZE * SIZE]);
        int previous = SnowFanField.FULL;
        for (int x = 10; x <= 16; x++) {
            int here = at(fan, x, 16);
            assertTrue(here <= previous, "never more snow further in: " + x + " " + here);
            previous = here;
        }
        assertTrue(at(fan, 10, 16) > 180, "thick at the canopy's edge");
        assertEquals(0, at(fan, 16, 16), "none at the trunk, six blocks in");
        int step1 = at(fan, 11, 16) - at(fan, 12, 16);
        int step2 = at(fan, 12, 16) - at(fan, 13, 16);
        assertEquals(step1, step2, 2, "a steady line, not patches");
    }

    @Test
    void theFalloffIsRoundNotSquare() {
        byte[] fan = fanOf(roundCanopy(SnowFanField.UNDER_TREE), new int[SIZE * SIZE]);
        // Two blocks in from the edge straight across and along the diagonal take about the same.
        int straight = at(fan, 12, 16);
        int diagonal = at(fan, 13, 13);
        assertEquals(straight, diagonal, 30);
    }

    @Test
    void overhangsReachLessFar() {
        byte[] fan = fanOf(roundCanopy(SnowFanField.UNDER_OVERHANG), new int[SIZE * SIZE]);
        assertTrue(at(fan, 11, 16) > 0);
        assertEquals(0, at(fan, 14, 16), "three blocks in under a roof, no snow");
    }

    @Test
    void wallsAndStepsStopIt() {
        byte[] kinds = new byte[SIZE * SIZE];
        int[] heights = new int[SIZE * SIZE];
        // Open snow on the west, a house to the east: a wall at x 10, floor beyond it.
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                kinds[z * SIZE + x] = x < 10 ? SnowFanField.SOURCE : x == 10 ? SnowFanField.BLOCKED : SnowFanField.UNDER_OVERHANG;
            }
        }
        byte[] fan = fanOf(kinds, heights);
        assertEquals(0, at(fan, 11, 5), "nothing comes through the wall");
        // A doorway in the wall at z 5: snow blows a little way in.
        kinds[5 * SIZE + 10] = SnowFanField.UNDER_OVERHANG;
        fan = fanOf(kinds, heights);
        assertTrue(at(fan, 10, 5) > 0);
        assertTrue(at(fan, 11, 5) > 0);
        // A floor a step up from the ground outside: no snow climbs it.
        Arrays.fill(heights, 0);
        for (int z = 0; z < SIZE; z++) {
            for (int x = 10; x < SIZE; x++) {
                heights[z * SIZE + x] = 3;
            }
        }
        fan = fanOf(kinds, heights);
        assertEquals(0, at(fan, 11, 5));
    }

    @Test
    void sourcesAreFullAndBlockedColumnsHaveNone() {
        byte[] fan = fanOf(roundCanopy(SnowFanField.UNDER_TREE), new int[SIZE * SIZE]);
        assertEquals(SnowFanField.FULL, at(fan, 0, 0));
        assertEquals(0, SnowFanField.amount(SnowFanField.BLOCKED, 0));
        assertEquals(SnowFanField.FULL, SnowFanField.amount(SnowFanField.UNDER_TREE, 0));
    }
}
