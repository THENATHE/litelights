package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Far snow's lines (FarSnowGrid, FarSnow), and the shader's tests on the codes
 * (shaders/include/farterrain/far_snow.glsl), mirrored here. Keep the mirror in step with the shader.
 */
class FarSnowGridTest {
    private static final int FLOOR = -64;
    private static final int SIZE = FarSnowGrid.SIZE;

    /** far_snow.glsl: snow lies on a surface at height y where the code is known and y - floor + 2 >= code. */
    private static boolean snowAt(int code, double surfaceY) {
        return code > 0 && surfaceY - FLOOR + 2.0 >= code;
    }

    @Test
    void codesKeepAlmanacsTestForEveryLine() {
        for (int line = FLOOR; line <= 320; line++) {
            int code = FarSnowGrid.code(line, FLOOR);
            for (int y = FLOOR - 1; y <= 321; y++) {
                assertEquals(y >= line, snowAt(code, y), "line " + line + ", surface " + y);
            }
        }
    }

    @Test
    void sentinelsBecomeUnknownEverywhereAndNowhere() {
        assertEquals(0, FarSnowGrid.code(FarSnowSource.UNKNOWN, FLOOR));
        int everywhere = FarSnowGrid.code(FarSnowSource.EVERYWHERE, FLOOR);
        int nowhere = FarSnowGrid.code(FarSnowSource.NOWHERE, FLOOR);
        for (int y = FLOOR; y <= 320; y++) {
            assertTrue(snowAt(everywhere, y));
            assertFalse(snowAt(nowhere, y));
            assertFalse(snowAt(0, y), "unknown is never snow (it is left as captured)");
        }
    }

    @Test
    void ringsFollowTheCameraInWholeSteps() {
        FarSnowGrid grid = new FarSnowGrid(16, 1024);
        assertTrue(grid.wantsMove(0, 0), "a ring that was never placed");
        int origin = grid.wantedOriginX(300.0);
        assertEquals(-4096, origin, "the middle snaps to whole steps");
        grid.swapIn(grid.takeSpare(), origin, grid.wantedOriginZ(-300.0));
        assertFalse(grid.wantsMove(1000.0, -1000.0));
        assertTrue(grid.wantsMove(1024.0, 0.0));
        assertEquals(1024 - 4096, grid.wantedOriginX(1024.0));
    }

    @Test
    void anAreaMarksTheTexelsWhoseColumnsItHolds() {
        FarSnowGrid grid = new FarSnowGrid(16, 1024);
        grid.swapIn(grid.takeSpare(), 0, 0);
        // Texel i is read at column 16 i + 8.
        assertEquals(new FarSnowGrid.Rect(0, 0, 0, 0), grid.texels(0, 0, 15, 15));
        assertEquals(new FarSnowGrid.Rect(1, 0, 1, 0), grid.texels(9, 0, 24, 8));
        assertNull(grid.texels(9, 0, 23, 8), "no texel's column lies in it");
        assertEquals(new FarSnowGrid.Rect(32, 32, 63, 63), grid.texels(512, 512, 1023, 1023), "a 512-block map tile");
        assertNull(grid.texels(-600, -600, -100, -100), "outside the ring");
        assertEquals(new FarSnowGrid.Rect(0, 0, SIZE - 1, SIZE - 1), grid.texels(Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2,
            Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2));
        grid.markDirty(new FarSnowSource.Area(0, 0, 15, 15));
        grid.markDirty(new FarSnowSource.Area(512, 512, 1023, 1023));
        assertEquals(new FarSnowGrid.Rect(0, 0, 63, 63), grid.takeDirty());
        assertNull(grid.takeDirty());
    }

    /**
     * far_snow.glsl's rule for a pixel near enough that single columns show: snow at and above the
     * bare line, the season's snow from the far snow line up on the columns the cover has passed.
     */
    private static boolean snowAt(int bareCode, int lineCode, double surfaceY, double cover, double columnThreshold) {
        if (!snowAt(lineCode, surfaceY)) {
            return false;
        }
        return snowAt(bareCode, surfaceY) || cover >= 1.0 || cover > columnThreshold;
    }

    @Test
    void theSeasonsSnowLiesBetweenTheBareLineAndTheLineOnTheCoversShare() {
        FarSnowGrid.Columns columns = new FarSnowGrid.Columns();
        FarSnowGrid.Rect one = new FarSnowGrid.Rect(0, 0, 0, 0);
        columns.set(one, new int[] {70}, new int[] {120}, null);
        int bare = FarSnowGrid.code(columns.bare[0], FLOOR);
        int line = FarSnowGrid.code(columns.now[0], FLOOR);
        assertTrue(snowAt(bare, line, 130, 0.0, 0.5), "above the bare line: always");
        assertFalse(snowAt(bare, line, 90, 0.4, 0.5), "between the lines, a column the cover hasn't reached");
        assertTrue(snowAt(bare, line, 90, 0.6, 0.5), "between the lines, a column it has");
        assertTrue(snowAt(bare, line, 90, 1.0, 0.999), "the whole cover");
        assertFalse(snowAt(bare, line, 60, 1.0, 0.0), "below the far snow line: never");
    }

    @Test
    void aColumnWithoutABareLineShowsItsLineWhateverTheCover() {
        FarSnowGrid.Columns columns = new FarSnowGrid.Columns();
        FarSnowGrid.Rect row = new FarSnowGrid.Rect(0, 0, 2, 0);
        // Snow everywhere over a bare line at 90; snow from 70 with a bare line that isn't known; nothing known.
        columns.set(row, new int[] {FarSnowSource.EVERYWHERE, 70, FarSnowSource.UNKNOWN}, new int[] {90, FarSnowSource.UNKNOWN, 120}, null);
        assertEquals(FarSnowSource.EVERYWHERE, columns.now[0]);
        assertEquals(90, columns.bare[0]);
        assertEquals(70, columns.bare[1]);
        assertEquals(FarSnowSource.UNKNOWN, columns.bare[2]);
        assertEquals(2, columns.known());
    }

    @Test
    void texelsCarryTheBareLineLowAndTheLineHigh() {
        FarSnowGrid.Columns columns = new FarSnowGrid.Columns();
        FarSnowGrid.Rect row = new FarSnowGrid.Rect(3, 5, 4, 5);
        columns.set(row, new int[] {70, FarSnowSource.NOWHERE}, new int[] {120, FarSnowSource.NOWHERE}, null);
        ByteBuffer out = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        columns.encode(row, FLOOR, out);
        assertEquals(8, out.limit());
        assertEquals(FarSnowGrid.code(120, FLOOR) | FarSnowGrid.code(70, FLOOR) << 16, out.getInt(0));
        assertEquals(FarSnowGrid.NOWHERE_CODE | FarSnowGrid.NOWHERE_CODE << 16, out.getInt(4));
    }

    @Test
    void theFarSnowLinesTopBitsSayWhichClimatesLayersTheColumnFollows() {
        FarSnowGrid.Columns columns = new FarSnowGrid.Columns();
        FarSnowGrid.Rect row = new FarSnowGrid.Rect(0, 0, 3, 0);
        columns.set(row, new int[] {70, 70, 70, 70}, new int[] {120, 120, 120, 120}, new byte[] {0, 1, 2, 3});
        ByteBuffer out = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        columns.encode(row, FLOOR, out);
        for (int area = 0; area < 4; area++) {
            int texel = out.getInt(area * 4);
            assertEquals(FarSnowGrid.code(120, FLOOR), texel & 0xFFFF);
            assertEquals(FarSnowGrid.code(70, FLOOR), texel >>> 16 & FarSnowGrid.NOWHERE_CODE, "the line itself is unchanged");
            assertEquals(area, texel >>> 30, "far_land_snow.glsl reads the area from the top two bits");
        }
    }

    @Test
    void colourTexelsAreRgbaInByteOrder() {
        assertEquals(0, FarSnow.texel(-1));
        assertEquals(0xFF5ABD91, FarSnow.texel(0x91BD5A));
    }

    @Test
    void cellsInsideOneBiomeAreTakenAsTheyAre() {
        int cells = FarSnow.COLOUR_SIZE * FarSnow.COLOUR_SIZE;
        int[] grass = new int[cells];
        int[] foliage = new int[cells];
        Arrays.fill(grass, 0x91BD59);
        Arrays.fill(foliage, 0x77AB2F);
        assertTrue(FarSnow.sameAround(grass, foliage, 10, 10));
        assertTrue(FarSnow.sameAround(grass, foliage, FarSnow.COLOUR_SIZE - 1, FarSnow.COLOUR_SIZE - 1), "the last cell has only itself");
        // A dappled forest's cell at (11, 11): the three cells whose blends reach it must blend.
        foliage[11 * FarSnow.COLOUR_SIZE + 11] = 0xE68E30;
        assertFalse(FarSnow.sameAround(grass, foliage, 10, 10));
        assertFalse(FarSnow.sameAround(grass, foliage, 11, 10));
        assertFalse(FarSnow.sameAround(grass, foliage, 10, 11));
        assertFalse(FarSnow.sameAround(grass, foliage, 11, 11));
        assertTrue(FarSnow.sameAround(grass, foliage, 12, 12));
    }
}
