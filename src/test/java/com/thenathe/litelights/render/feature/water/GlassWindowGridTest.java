package com.thenathe.litelights.render.feature.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The glass windows (GlassWindowGrid): which glass makes one window, how far each block lies from
 * its edges, the window's size, and the table include/glass/weather.glsl (lite_glass_window) reads.
 */
class GlassWindowGridTest {
    private static final int MIN_Y = -64;

    private static int uLow(int data) {
        return data & 63;
    }

    private static int uHigh(int data) {
        return (data >> 6) & 63;
    }

    private static int vLow(int data) {
        return (data >> 12) & 63;
    }

    private static int vHigh(int data) {
        return (data >> 18) & 63;
    }

    private static int size(int data) {
        return data >>> 24;
    }

    private static GlassWindowGrid build(GlassWindowGrid.Cells cells) {
        GlassWindowGrid grid = new GlassWindowGrid();
        grid.build(cells, 0, 64, 0, MIN_Y);
        return grid;
    }

    /** Full glass filling x = x0, y from y0 to y1, z from z0 to z1 (a window facing east and west). */
    private static void wall(GlassWindowGrid.Cells cells, int x0, int y0, int y1, int z0, int z1) {
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                cells.add(x0, y, z, GlassWindowGrid.FULL);
            }
        }
    }

    @Test
    void aSingleBlockIsAWindowOfOneOnEveryAxis() {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        cells.add(3, 70, -2, GlassWindowGrid.FULL);
        GlassWindowGrid grid = build(cells);
        for (int axis = GlassWindowGrid.AXIS_X; axis <= GlassWindowGrid.AXIS_Z; axis++) {
            int data = grid.find(3, 70, -2, axis, MIN_Y);
            assertNotEquals(0, data, "axis " + axis);
            assertEquals(0, uLow(data) + uHigh(data) + vLow(data) + vHigh(data));
            assertEquals(1, size(data));
        }
        assertEquals(3, grid.entries());
    }

    @Test
    void blocksKnowHowFarTheirWindowReachesEachWay() {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        // Five wide along z (0 to 4), four high (65 to 68), at x = 10.
        wall(cells, 10, 65, 68, 0, 4);
        GlassWindowGrid grid = build(cells);
        int corner = grid.find(10, 65, 0, GlassWindowGrid.AXIS_X, MIN_Y);
        assertEquals(0, uLow(corner));
        assertEquals(4, uHigh(corner));
        assertEquals(0, vLow(corner), "the bottom row");
        assertEquals(3, vHigh(corner));
        assertEquals(4, size(corner), "the smaller side");
        int middle = grid.find(10, 67, 2, GlassWindowGrid.AXIS_X, MIN_Y);
        assertEquals(2, uLow(middle));
        assertEquals(2, uHigh(middle));
        assertEquals(2, vLow(middle));
        assertEquals(1, vHigh(middle));
        assertEquals(4, size(middle), "every block of a window has its size");
        // u along z, v along y on faces looking along x; on top faces u is x and v is z.
        int top = grid.find(10, 68, 1, GlassWindowGrid.AXIS_Y, MIN_Y);
        assertEquals(0, uLow(top) + uHigh(top), "the top row is one block deep along x");
        assertEquals(1, vLow(top));
        assertEquals(3, vHigh(top));
        assertEquals(1, size(top));
        assertEquals(0, grid.find(10, 66, 1, GlassWindowGrid.AXIS_Y, MIN_Y), "faces inside the window don't look up");
    }

    @Test
    void separateWindowsDontJoinAndMixedColoursDo() {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        // Two 2 x 2 windows at x = 0 with a gap of wall at z = 2 between them.
        wall(cells, 0, 65, 66, 0, 1);
        wall(cells, 0, 65, 66, 3, 4);
        GlassWindowGrid grid = build(cells);
        int left = grid.find(0, 65, 1, GlassWindowGrid.AXIS_X, MIN_Y);
        assertEquals(0, uHigh(left), "the window ends at the gap");
        int right = grid.find(0, 65, 3, GlassWindowGrid.AXIS_X, MIN_Y);
        assertEquals(0, uLow(right));
        assertEquals(2, size(left));
        assertEquals(2, size(right));
    }

    @Test
    void anIrregularWindowTakesTheSizeOfItsBoundsEverywhere() {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        // An L: a column three high at z = 0 and a foot two long at the bottom (z 1 to 2).
        wall(cells, 0, 65, 67, 0, 0);
        wall(cells, 0, 65, 65, 1, 2);
        GlassWindowGrid grid = build(cells);
        assertEquals(3, size(grid.find(0, 67, 0, GlassWindowGrid.AXIS_X, MIN_Y)));
        assertEquals(3, size(grid.find(0, 65, 2, GlassWindowGrid.AXIS_X, MIN_Y)), "one size for the whole window, no step between blocks");
        int foot = grid.find(0, 65, 2, GlassWindowGrid.AXIS_X, MIN_Y);
        assertEquals(2, uLow(foot));
        assertEquals(0, vHigh(foot));
    }

    @Test
    void theInsideOfSolidGlassHasNoFaces() {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        // Three thick along x.
        for (int x = 0; x <= 2; x++) {
            wall(cells, x, 65, 65, 0, 0);
        }
        GlassWindowGrid grid = build(cells);
        assertEquals(0, grid.find(1, 65, 0, GlassWindowGrid.AXIS_X, MIN_Y), "covered on both sides along x");
        assertNotEquals(0, grid.find(0, 65, 0, GlassWindowGrid.AXIS_X, MIN_Y));
        assertNotEquals(0, grid.find(1, 65, 0, GlassWindowGrid.AXIS_Z, MIN_Y), "its sides still show");
    }

    @Test
    void panesJoinWhereTheyConnectAndShowFacesAcrossTheirRun() {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        int run = GlassWindowGrid.PANE | GlassWindowGrid.NORTH | GlassWindowGrid.SOUTH;
        // A pane window running north to south along x = 5: z 0 to 2, two high. The ends join only inward.
        for (int y = 65; y <= 66; y++) {
            cells.add(5, y, 0, GlassWindowGrid.PANE | GlassWindowGrid.SOUTH);
            cells.add(5, y, 1, run);
            cells.add(5, y, 2, GlassWindowGrid.PANE | GlassWindowGrid.NORTH);
        }
        // A pane beside it at z = 3 that doesn't join it (a separate window touching it).
        cells.add(5, 65, 3, GlassWindowGrid.PANE | GlassWindowGrid.SOUTH);
        GlassWindowGrid grid = build(cells);
        int end = grid.find(5, 65, 0, GlassWindowGrid.AXIS_X, MIN_Y);
        assertEquals(0, uLow(end));
        assertEquals(2, uHigh(end), "the window stops where the panes stop joining");
        assertEquals(1, vHigh(end), "stacked panes join");
        assertEquals(2, size(end));
        assertEquals(0, grid.find(5, 65, 1, GlassWindowGrid.AXIS_Z, MIN_Y), "a pane running along z shows no faces along z");
        assertEquals(0, grid.find(5, 65, 1, GlassWindowGrid.AXIS_Y, MIN_Y), "nor up");
        int apart = grid.find(5, 65, 3, GlassWindowGrid.AXIS_X, MIN_Y);
        assertEquals(0, uLow(apart));
        assertEquals(1, size(apart));
    }

    @Test
    void panesAndFullBlocksAreSeparateWindows() {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        cells.add(0, 65, 0, GlassWindowGrid.FULL);
        cells.add(0, 65, 1, GlassWindowGrid.PANE | GlassWindowGrid.NORTH | GlassWindowGrid.SOUTH);
        GlassWindowGrid grid = build(cells);
        assertEquals(0, uHigh(grid.find(0, 65, 0, GlassWindowGrid.AXIS_X, MIN_Y)), "the pane's face lies in another plane");
        assertEquals(0, uLow(grid.find(0, 65, 1, GlassWindowGrid.AXIS_X, MIN_Y)));
    }

    @Test
    void runsAndSizesAreCapped() {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        // 100 long and 2 high: runs stop counting at 63.
        wall(cells, 0, 65, 66, 0, 99);
        GlassWindowGrid grid = build(cells);
        int start = grid.find(0, 65, 0, GlassWindowGrid.AXIS_X, MIN_Y);
        assertEquals(63, uHigh(start));
        assertEquals(2, size(start));
        int middle = grid.find(0, 65, 50, GlassWindowGrid.AXIS_X, MIN_Y);
        assertEquals(50, uLow(middle));
        assertEquals(49, uHigh(middle));
    }

    @Test
    void theTableKeepsTheNearestWhenThereIsTooMuchGlass() {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        // Separate single blocks (every other block), each with three entries: more than the table takes.
        int side = 80;
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                cells.add(x * 2 - side, 64, z * 2 - side, GlassWindowGrid.FULL);
            }
        }
        GlassWindowGrid grid = new GlassWindowGrid();
        grid.build(cells, 0, 64, 0, MIN_Y);
        assertTrue(grid.entries() <= GlassWindowGrid.MOST_ENTRIES);
        assertTrue(grid.entries() > GlassWindowGrid.MOST_ENTRIES * 9 / 10, "nearly full: " + grid.entries());
        assertNotEquals(0, grid.find(0, 64, 0, GlassWindowGrid.AXIS_X, MIN_Y), "the block at the camera is in");
        assertEquals(0, grid.find(-side, 64, -side, GlassWindowGrid.AXIS_X, MIN_Y), "the farthest corner is left out");
    }

    @Test
    void keysAndSlotsAreWhatTheShaderComputes() {
        // The shader: key = 1 | axis << 1 | (x & 255) << 3 | (z & 255) << 11 | ((y - minY) & 4095) << 19,
        // slot = (key * 0x9E3779B1u) >> 18u, two texels an entry.
        int key = GlassWindowGrid.key(-3, 70, 300, GlassWindowGrid.AXIS_Z, MIN_Y);
        assertEquals(1 | 2 << 1 | 253 << 3 | 44 << 11 | 134 << 19, key);
        long product = (key & 0xFFFFFFFFL) * 0x9E3779B1L & 0xFFFFFFFFL;
        assertEquals((int) (product >>> 18), GlassWindowGrid.slot(key));
        assertTrue(GlassWindowGrid.slot(key) < GlassWindowGrid.CAPACITY);

        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        cells.add(-3, 70, 300, GlassWindowGrid.FULL);
        GlassWindowGrid grid = build(cells);
        int slot = GlassWindowGrid.slot(key);
        boolean found = false;
        for (int probe = 0; probe < GlassWindowGrid.MAX_PROBES && !found; probe++) {
            int at = (slot + probe) & (GlassWindowGrid.CAPACITY - 1);
            found = grid.texels()[at * 2] == key;
        }
        assertTrue(found, "within the shader's probes");
        assertEquals(GlassWindowGrid.CAPACITY * 2, GlassWindowGrid.WIDTH * GlassWindowGrid.ROWS);
    }

    @Test
    void rebuildingForgetsGlassThatWent() {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        cells.add(0, 65, 0, GlassWindowGrid.FULL);
        GlassWindowGrid grid = new GlassWindowGrid();
        grid.build(cells, 0, 64, 0, MIN_Y);
        cells.clear();
        grid.build(cells, 0, 64, 0, MIN_Y);
        assertEquals(0, grid.entries());
        assertEquals(0, grid.find(0, 65, 0, GlassWindowGrid.AXIS_X, MIN_Y));
    }
}
