package com.thenathe.litelights.render.feature.skyfog;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudShapesTest {
    private static final int SIZE = 256;

    @Test
    void mapsAreTheSameEveryTime() {
        assertArrayEquals(CloudShapes.cumulus(SIZE), CloudShapes.cumulus(SIZE));
        assertArrayEquals(CloudShapes.altocumulus(SIZE), CloudShapes.altocumulus(SIZE));
    }

    @Test
    void coverageIsNearTheTarget() {
        assertEquals(0.30, coverage(CloudShapes.cumulus(SIZE)), 0.04);
        assertEquals(0.22, coverage(CloudShapes.altocumulus(SIZE)), 0.04);
    }

    @Test
    void noLoneSpecks() {
        byte[] cells = CloudShapes.cumulus(SIZE);
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                byte flags = cells[x + z * SIZE];
                if ((flags & CloudShapes.FILLED) != 0) {
                    int open = Integer.bitCount(flags & (CloudShapes.NORTH_OPEN | CloudShapes.EAST_OPEN | CloudShapes.SOUTH_OPEN | CloudShapes.WEST_OPEN));
                    assertTrue(open < 4, "a lone cell at " + x + ", " + z);
                }
            }
        }
    }

    @Test
    void openFlagsMatchNeighboursAcrossTheWrap() {
        byte[] cells = CloudShapes.cumulus(SIZE);
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                byte flags = cells[x + z * SIZE];
                if ((flags & CloudShapes.FILLED) == 0) {
                    continue;
                }
                assertEquals(!filled(cells, x, z - 1), (flags & CloudShapes.NORTH_OPEN) != 0);
                assertEquals(!filled(cells, x + 1, z), (flags & CloudShapes.EAST_OPEN) != 0);
                assertEquals(!filled(cells, x, z + 1), (flags & CloudShapes.SOUTH_OPEN) != 0);
                assertEquals(!filled(cells, x - 1, z), (flags & CloudShapes.WEST_OPEN) != 0);
            }
        }
    }

    @Test
    void noiseRepeatsAcrossTheMap() {
        for (int i = 0; i < 50; i++) {
            float x = i * 3.7f;
            float z = i * 1.3f;
            assertEquals(CloudShapes.periodicNoise(x, z, SIZE, 16, 16, 7L), CloudShapes.periodicNoise(x + SIZE, z - SIZE, SIZE, 16, 16, 7L), 1.0e-5f);
        }
    }

    private static boolean filled(byte[] cells, int x, int z) {
        return (cells[Math.floorMod(x, SIZE) + Math.floorMod(z, SIZE) * SIZE] & CloudShapes.FILLED) != 0;
    }

    private static double coverage(byte[] cells) {
        int filled = 0;
        for (byte cell : cells) {
            filled += (cell & CloudShapes.FILLED) != 0 ? 1 : 0;
        }
        return filled / (double) cells.length;
    }
}
