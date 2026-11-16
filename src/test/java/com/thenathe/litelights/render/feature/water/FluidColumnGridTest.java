package com.thenathe.litelights.render.feature.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidColumnGridTest {
    private static final int MIN_Y = -64;
    /** The top face of the land around the test water: grass at y 62, level with a source block. */
    private static final int LAND_TOP = 63;

    private static int water(double surfaceY) {
        return FluidColumnGrid.column(surfaceY, MIN_Y, LAND_TOP, false, false, false, false);
    }

    private static int red(int texel) {
        return texel & 0xFF;
    }

    private static int green(int texel) {
        return (texel >>> 8) & 0xFF;
    }

    private static int blue(int texel) {
        return (texel >>> 16) & 0xFF;
    }

    private static int alpha(int texel) {
        return texel >>> 24;
    }

    /** A 5 x 5 pond of water at y = 62.875 (a source block's surface) around (0, 0), on loaded dry land. */
    private static FluidColumnGrid pond() {
        FluidColumnGrid grid = new FluidColumnGrid();
        for (int z = -6; z <= 6; z++) {
            for (int x = -6; x <= 6; x++) {
                boolean inPond = Math.abs(x) <= 2 && Math.abs(z) <= 2;
                grid.set(x, z, inPond ? water(62.875) : FluidColumnGrid.column(Double.NaN, MIN_Y, LAND_TOP, false, false, false, false));
            }
        }
        return grid;
    }

    @Test
    void theEdgeOfTheLoadedLandIsNoShore() {
        FluidColumnGrid grid = new FluidColumnGrid();
        grid.set(0, 0, water(62.875));
        grid.set(1, 0, water(62.875));
        grid.set(-1, 0, FluidColumnGrid.column(Double.NaN, MIN_Y, LAND_TOP, false, false, false, false));
        int shore = blue(grid.texel(0, 0));
        assertEquals(FluidColumnGrid.WEST, shore & FluidColumnGrid.WEST, "loaded dry land to the west is a shore");
        assertEquals(0, shore & (FluidColumnGrid.NORTH | FluidColumnGrid.SOUTH), "columns nobody has loaded are not");
    }

    @Test
    void surfaceHeightSurvivesPackingToTheTexel() {
        FluidColumnGrid grid = pond();
        int texel = grid.texel(0, 0);
        int steps = red(texel) | (green(texel) & 0x7F) << 8;
        assertEquals((62.875 - MIN_Y) * FluidColumnGrid.HEIGHT_STEPS_PER_BLOCK, steps);
        assertEquals(62.875, FluidColumnGrid.surfaceY(grid.get(0, 0), MIN_Y), 1.0e-9);
    }

    @Test
    void noFluidPacksToAnEmptyTexel() {
        FluidColumnGrid grid = new FluidColumnGrid();
        grid.set(3, 3, FluidColumnGrid.column(Double.NaN, MIN_Y, LAND_TOP, false, false, true, false));
        assertFalse(FluidColumnGrid.hasFluid(grid.get(3, 3)));
        assertEquals(0, grid.texel(3, 3), "a dry column tells the shader nothing, even under a canopy");
        assertTrue((grid.get(3, 3) & FluidColumnGrid.KNOWN) != 0, "but it is known to be dry");
    }

    @Test
    void lavaSetsTheTopBitOfGreen() {
        FluidColumnGrid grid = new FluidColumnGrid();
        grid.set(0, 0, FluidColumnGrid.column(40.0, MIN_Y, LAND_TOP, true, false, false, false));
        assertEquals(0x80, green(grid.texel(0, 0)) & 0x80);
    }

    @Test
    void middleOfAPondHasNoShore() {
        assertEquals(0, blue(pond().texel(0, 0)));
    }

    @Test
    void edgeColumnsSeeTheLandAroundThem() {
        FluidColumnGrid grid = pond();
        int west = FluidColumnGrid.WEST | FluidColumnGrid.NORTH_WEST | FluidColumnGrid.SOUTH_WEST;
        assertEquals(west, blue(grid.texel(-2, 0)));
        int northEastCorner = FluidColumnGrid.EAST | FluidColumnGrid.NORTH | FluidColumnGrid.NORTH_EAST
            | FluidColumnGrid.NORTH_WEST | FluidColumnGrid.SOUTH_EAST;
        assertEquals(northEastCorner, blue(grid.texel(2, -2)));
    }

    @Test
    void aDifferentFluidEndsTheShoreToo() {
        FluidColumnGrid grid = pond();
        grid.set(1, 0, FluidColumnGrid.column(62.875, MIN_Y, LAND_TOP, true, false, false, false));
        assertTrue((blue(grid.texel(0, 0)) & FluidColumnGrid.EAST) != 0);
    }

    @Test
    void waterFallingInFromAHigherNeighbourMarksTheLanding() {
        FluidColumnGrid grid = pond();
        grid.set(0, -3, FluidColumnGrid.column(75.0, MIN_Y, LAND_TOP, false, false, false, true));
        int landing = alpha(grid.texel(0, -2)) >> 4;
        assertEquals(12, landing, "the fall from 75 to 62.875 is twelve whole blocks");
        assertEquals(0, alpha(grid.texel(0, 1)) >> 4, "two columns away there is no landing");
    }

    @Test
    void aSlightlyHigherNeighbourIsNotAWaterfall() {
        FluidColumnGrid grid = pond();
        grid.set(3, 0, water(63.2));
        assertEquals(0, alpha(grid.texel(2, 0)) >> 4);
    }

    @Test
    void canopyDensityCountsCanopyColumnsNearby() {
        FluidColumnGrid grid = pond();
        grid.set(4, 0, FluidColumnGrid.column(Double.NaN, MIN_Y, LAND_TOP, false, false, true, false));
        grid.set(4, 1, FluidColumnGrid.column(Double.NaN, MIN_Y, LAND_TOP, false, false, true, false));
        assertEquals(2, alpha(grid.texel(2, 0)) & 0xF);
        assertEquals(0, alpha(grid.texel(0, 0)) & 0xF, "canopy three columns away is too far");
    }

    @Test
    void theGridWrapsAroundSoNeighboursAcrossTheEdgeAreFound() {
        FluidColumnGrid grid = new FluidColumnGrid();
        grid.set(255, 0, water(10.0));
        assertEquals(grid.get(255, 0), grid.get(-1, 0));
        assertEquals(grid.get(255, 0), grid.get(511, 256));
    }

    @Test
    void clearingAChunkEmptiesExactlyItsColumns() {
        FluidColumnGrid grid = pond();
        grid.set(16, 0, water(20.0));
        grid.clearChunk(-1, -1);
        assertFalse(FluidColumnGrid.hasFluid(grid.get(-1, -1)));
        assertTrue(FluidColumnGrid.hasFluid(grid.get(0, 0)));
        assertTrue(FluidColumnGrid.hasFluid(grid.get(16, 0)));
    }

    @Test
    void unknownNeighboursAreNeverShore() {
        FluidColumnGrid grid = new FluidColumnGrid();
        grid.set(0, 0, water(62.875));
        // (1, 0) was never set: its chunk isn't loaded.
        assertEquals(0, blue(grid.texel(0, 0)) & FluidColumnGrid.EAST);
    }

    @Test
    void landWrappedInFromTheFarSideOfTheGridIsNotShore() {
        FluidColumnGrid grid = new FluidColumnGrid();
        grid.setWindow(0, 0);
        int last = FluidColumnGrid.SIZE - 1;
        for (int z = -1; z <= 1; z++) {
            grid.set(last - 1, z + 8, water(62.875));
            grid.set(last, z + 8, water(62.875));
            // Column SIZE wraps onto column 0, which is land inside the window.
            grid.set(0, z + 8, FluidColumnGrid.column(Double.NaN, MIN_Y, LAND_TOP, false, false, false, false));
        }
        assertEquals(0, blue(grid.texel(last, 8)) & (FluidColumnGrid.EAST | FluidColumnGrid.NORTH_EAST | FluidColumnGrid.SOUTH_EAST),
            "the column past the window's edge is unknown, not the land that wrapped into its cell");
        assertEquals(0, grid.get(FluidColumnGrid.SIZE, 8));
    }

    @Test
    void dryGroundBelowTheWaterlineIsNotShore() {
        FluidColumnGrid grid = new FluidColumnGrid();
        grid.set(0, 0, water(62.875));
        // The ground beside the water ends a block lower: the water runs over the edge there.
        grid.set(1, 0, FluidColumnGrid.column(Double.NaN, MIN_Y, 62, false, false, false, false));
        grid.set(-1, 0, FluidColumnGrid.column(Double.NaN, MIN_Y, LAND_TOP, false, false, false, false));
        int shore = blue(grid.texel(0, 0));
        assertEquals(0, shore & FluidColumnGrid.EAST);
        assertEquals(FluidColumnGrid.WEST, shore & FluidColumnGrid.WEST);
    }
}
