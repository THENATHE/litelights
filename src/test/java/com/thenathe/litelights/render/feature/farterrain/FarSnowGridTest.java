package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Far snow's lines and changes (FarSnowGrid, FarSnow), and the shader's test on the codes
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
        grid.moveTo(origin, grid.wantedOriginZ(-300.0), unknownLines(), false);
        assertFalse(grid.wantsMove(1000.0, -1000.0));
        assertTrue(grid.wantsMove(1024.0, 0.0));
        assertEquals(1024 - 4096, grid.wantedOriginX(1024.0));
    }

    @Test
    void anAreaMarksTheTexelsWhoseColumnsItHolds() {
        FarSnowGrid grid = new FarSnowGrid(16, 1024);
        grid.moveTo(0, 0, unknownLines(), false);
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

    @Test
    void aMapTileNeverAnimatesButASeasonChangeKeepsItsBefore() {
        FarSnowGrid grid = new FarSnowGrid(16, 1024);
        grid.moveTo(0, 0, filled(100), false);
        FarSnowGrid.Rect rect = new FarSnowGrid.Rect(0, 0, 1, 1);
        grid.accept(rect, new int[] {70, 70, 70, 70});
        assertEquals(70, grid.before[0], "seen better, not changed");
        assertEquals(0, grid.changing());
        int[] nowhere = filled(FarSnowSource.NOWHERE);
        assertEquals(SIZE * SIZE, grid.countChanges(FarSnowGrid.Rect.ALL, nowhere));
        grid.acceptChange(FarSnowGrid.Rect.ALL, nowhere);
        assertEquals(70, grid.before[0]);
        assertEquals(100, grid.before[2]);
        assertEquals(FarSnowSource.NOWHERE, grid.now[0]);
        assertEquals(SIZE * SIZE, grid.changing());
        grid.settle();
        assertEquals(0, grid.changing());
    }

    @Test
    void aChangeThatChangesNothingLeavesTheOneShowingAlone() {
        FarSnowGrid grid = new FarSnowGrid(16, 1024);
        grid.moveTo(0, 0, filled(FarSnowSource.EVERYWHERE), false);
        grid.acceptChange(FarSnowGrid.Rect.ALL, filled(FarSnowSource.NOWHERE));
        assertEquals(0, grid.countChanges(FarSnowGrid.Rect.ALL, filled(FarSnowSource.NOWHERE)));
        grid.accept(FarSnowGrid.Rect.ALL, filled(FarSnowSource.NOWHERE));
        assertEquals(SIZE * SIZE, grid.changing(), "a tile with the same lines keeps the change going");
    }

    @Test
    void aColumnFirstKnownInAChangeDoesntAnimate() {
        FarSnowGrid grid = new FarSnowGrid(16, 1024);
        grid.moveTo(0, 0, unknownLines(), false);
        assertEquals(0, grid.countChanges(FarSnowGrid.Rect.ALL, filled(80)));
        grid.acceptChange(FarSnowGrid.Rect.ALL, filled(80));
        assertEquals(80, grid.before[123]);
        assertEquals(0, grid.changing());
    }

    @Test
    void movingKeepsTheChangeWhereTheRingsOverlap() {
        FarSnowGrid grid = new FarSnowGrid(16, 1024);
        grid.moveTo(0, 0, filled(FarSnowSource.EVERYWHERE), false);
        grid.acceptChange(FarSnowGrid.Rect.ALL, filled(FarSnowSource.NOWHERE));
        // One step east: texel x holds what texel x + 64 held.
        grid.moveTo(1024, 0, filled(FarSnowSource.NOWHERE), true);
        assertEquals(FarSnowSource.EVERYWHERE, grid.before[0]);
        assertEquals(FarSnowSource.EVERYWHERE, grid.before[SIZE - 65]);
        assertEquals(FarSnowSource.NOWHERE, grid.before[SIZE - 64], "new land isn't animated");
        assertEquals(FarSnowSource.NOWHERE, grid.before[SIZE - 1]);
        grid.moveTo(2048, 0, filled(FarSnowSource.NOWHERE), false);
        assertEquals(0, grid.changing(), "without a change running, nothing is held");
    }

    @Test
    void layersRoundHalfUpAsAlmanacLaysThem() {
        assertEquals(0, FarSnow.wholeLayers(-1.0));
        assertEquals(0, FarSnow.wholeLayers(0.0));
        assertEquals(0, FarSnow.wholeLayers(0.4375));
        assertEquals(1, FarSnow.wholeLayers(0.5));
        assertEquals(1, FarSnow.wholeLayers(1.0));
    }

    @Test
    void formingFollowsTheDepthWithoutJumping() {
        long longest = FarSnow.FORMING_TICKS_AT_MOST;
        // The line turns at half a layer: the first half comes over 50 ticks, not at once.
        assertEquals(0.0, FarSnow.formingProgress(0, 0.5, longest), 1.0e-9);
        assertEquals(0.25, FarSnow.formingProgress(25, 0.5, longest), 1.0e-9);
        assertEquals(0.5, FarSnow.formingProgress(60, 0.5, longest), 1.0e-9);
        // Then as deep as the snow is...
        assertEquals(0.75, FarSnow.formingProgress(1000, 0.75, longest), 1.0e-9);
        // ...and done within the longest time even if the snowfall stops.
        assertEquals(1.0, FarSnow.formingProgress(longest, 0.6, longest), 1.0e-9);
    }

    @Test
    void fromAlmanac013FormingIsTimedLikeTheThaw() {
        long thaw = 48_000L;
        // Near snow forms in patches over the whole thaw time, so far snow takes as long, not 12,000 ticks.
        assertEquals(0.25, FarSnow.changeProgress(12_000, thaw, false, 1.0), 1.0e-9);
        assertEquals(0.5, FarSnow.changeProgress(24_000, thaw, false, 1.0), 1.0e-9);
        assertEquals(1.0, FarSnow.changeProgress(60_000, thaw, false, 1.0), 1.0e-9);
        // Against 0.1.2 a snowfall's change follows the depth, done within 12,000 ticks at most.
        assertEquals(1.0, FarSnow.changeProgress(12_000, thaw, true, 0.6), 1.0e-9);
        assertEquals(0.0, FarSnow.changeProgress(-5, thaw, false, 0.0), 1.0e-9);
    }

    @Test
    void onlyA012ServersFirstLayerFollowsTheDepth() {
        int midWinter = 7;
        assertTrue(FarSnow.followsDepth(-1, midWinter, midWinter, 0, 1), "0.1.2: it has just snowed");
        assertFalse(FarSnow.followsDepth(14_884, midWinter, midWinter, 0, 1), "0.1.3 says when it began: timed");
        assertFalse(FarSnow.followsDepth(-1, midWinter, midWinter - 1, 0, 1), "a new sub-season is timed");
        assertFalse(FarSnow.followsDepth(-1, midWinter, midWinter, 1, 0), "melting is timed");
    }

    @Test
    void joiningPicksUpOnlyAChangeStillUnderWay() {
        long thaw = 6_000L;
        assertTrue(FarSnow.underWay(10_000, 10_000, thaw));
        assertTrue(FarSnow.underWay(10_000, 15_999, thaw));
        assertFalse(FarSnow.underWay(10_000, 16_000, thaw), "over");
        assertFalse(FarSnow.underWay(10_000, 9_000, thaw), "a tick ahead of the game time counts for nothing");
        // Every player is as far through as the server's tick says, whenever they joined.
        assertEquals(0.5, FarSnow.changeProgress(13_000 - 10_000, thaw, false, 0.0), 1.0e-9);
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

    private static int[] filled(int line) {
        int[] lines = new int[SIZE * SIZE];
        Arrays.fill(lines, line);
        return lines;
    }

    private static int[] unknownLines() {
        return filled(FarSnowSource.UNKNOWN);
    }
}
