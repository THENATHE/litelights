package com.thenathe.litelights.render.feature.water;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shore foam only at the real waterline: water under a dock, a bridge or an overhang is water, not
 * land, so the foam never traces the outline of what hangs over the lake.
 */
class CoveredWaterTest {
    private static final int MIN_Y = -64;
    private static final double LAKE = 62.875;
    private static final int LAKE_BLOCK = 62;
    private static final int LAND_TOP = 63;

    /** Blocks the test sets: water surfaces by (x, y, z); everything else is dry. */
    private static final class World implements CoveredWater.Blocks {
        private final Map<String, Double> surfaces = new HashMap<>();

        void water(int x, int y, int z, double surface) {
            surfaces.put(x + "," + y + "," + z, surface);
        }

        @Override
        public double waterSurface(int x, int y, int z) {
            return surfaces.getOrDefault(x + "," + y + "," + z, Double.NaN);
        }
    }

    private static int openWater() {
        return FluidColumnGrid.column(LAKE, MIN_Y, LAND_TOP, false, false, false, false);
    }

    private static int dry(int solidTop) {
        return FluidColumnGrid.column(Double.NaN, MIN_Y, solidTop, false, false, false, false);
    }

    private static int shore(FluidColumnGrid grid, int x, int z) {
        return (grid.texel(x, z) >>> 16) & 0xFF;
    }

    /**
     * The chunk at (0, 0): a lake over x 0..15 at y 62.875, with a plank roof at y 66 over the
     * columns x 4..6, z 4..6 standing on nothing, and grass land at z 12 and beyond.
     */
    private static FluidColumnGrid lakeWithRoof(World world) {
        FluidColumnGrid grid = new FluidColumnGrid();
        for (int z = -1; z <= 16; z++) {
            for (int x = -1; x <= 16; x++) {
                grid.set(x, z, z >= 12 ? dry(LAND_TOP) : openWater());
            }
        }
        for (int z = 4; z <= 6; z++) {
            for (int x = 4; x <= 6; x++) {
                grid.set(x, z, dry(67));
                world.water(x, LAKE_BLOCK, z, LAKE);
            }
        }
        return grid;
    }

    @Test
    void waterUnderARoofIsWaterAndMakesNoFoam() {
        World world = new World();
        FluidColumnGrid grid = lakeWithRoof(world);
        assertEquals(FluidColumnGrid.EAST, shore(grid, 3, 5) & FluidColumnGrid.EAST, "before: the roof reads as land beside the lake");

        int found = new CoveredWater().flood(grid, MIN_Y, 0, 0, world, (x, z) -> { });

        assertEquals(9, found);
        for (int z = 4; z <= 6; z++) {
            for (int x = 4; x <= 6; x++) {
                assertTrue(FluidColumnGrid.hasFluid(grid.get(x, z)), "water under the roof at " + x + ", " + z);
                assertEquals(LAKE, FluidColumnGrid.surfaceY(grid.get(x, z), MIN_Y), 1.0e-9);
            }
        }
        for (int z = 3; z <= 7; z++) {
            for (int x = 3; x <= 7; x++) {
                assertEquals(0, shore(grid, x, z), "no foam around the roof's outline at " + x + ", " + z);
            }
        }
        assertEquals(FluidColumnGrid.SOUTH, shore(grid, 5, 11) & FluidColumnGrid.SOUTH, "the real shore keeps its foam");
    }

    @Test
    void aPoolInACaveBelowTheLandStaysLand() {
        World world = new World();
        FluidColumnGrid grid = lakeWithRoof(world);
        // Water 20 blocks down under the land at z 12: not at the lake's level, so not the lake.
        world.water(5, 42, 12, 42.875);
        new CoveredWater().flood(grid, MIN_Y, 0, 0, world, (x, z) -> { });
        assertFalse(FluidColumnGrid.hasFluid(grid.get(5, 12)));
        assertEquals(FluidColumnGrid.SOUTH, shore(grid, 5, 11) & FluidColumnGrid.SOUTH, "the land over the cave is still the shore");
    }

    @Test
    void aWallAtTheWaterlineUnderTheRoofGetsFoam() {
        World world = new World();
        FluidColumnGrid grid = lakeWithRoof(world);
        // The roof's west column is a solid wall down into the water instead of a roof over it.
        world.surfaces.remove("4," + LAKE_BLOCK + ",5");
        new CoveredWater().flood(grid, MIN_Y, 0, 0, world, (x, z) -> { });
        assertFalse(FluidColumnGrid.hasFluid(grid.get(4, 5)));
        assertEquals(FluidColumnGrid.WEST, shore(grid, 5, 5) & FluidColumnGrid.WEST, "covered water against a wall foams at the wall");
        assertEquals(FluidColumnGrid.EAST, shore(grid, 3, 5) & FluidColumnGrid.EAST, "and so does open water");
    }

    @Test
    void coveredWaterCarryingOnIntoTheNextChunkIsReportedNotChanged() {
        World world = new World();
        FluidColumnGrid grid = new FluidColumnGrid();
        for (int z = -1; z <= 16; z++) {
            for (int x = -1; x <= 16; x++) {
                grid.set(x, z, x <= 10 ? openWater() : dry(67));
            }
        }
        for (int x = 11; x <= 16; x++) {
            world.water(x, LAKE_BLOCK, 3, LAKE);
        }
        List<String> reported = new ArrayList<>();
        int found = new CoveredWater().flood(grid, MIN_Y, 0, 0, world, (x, z) -> reported.add(x + "," + z));
        assertEquals(5, found, "columns 11 to 15 of this chunk");
        assertEquals(List.of("16,3"), reported);
        assertFalse(FluidColumnGrid.hasFluid(grid.get(16, 3)), "the neighbouring chunk is scanned on its own");
    }

    @Test
    void waterInTheNextChunkSeedsTheSearch() {
        World world = new World();
        FluidColumnGrid grid = new FluidColumnGrid();
        for (int z = -1; z <= 16; z++) {
            for (int x = -1; x <= 16; x++) {
                grid.set(x, z, dry(67));
            }
        }
        grid.set(-1, 8, openWater());
        world.water(0, LAKE_BLOCK, 8, LAKE);
        world.water(1, LAKE_BLOCK, 8, LAKE);
        assertEquals(2, new CoveredWater().flood(grid, MIN_Y, 0, 0, world, (x, z) -> { }));
        assertTrue(FluidColumnGrid.hasFluid(grid.get(1, 8)));
    }

    @Test
    void unknownColumnsAreNeverSearchedOrShore() {
        World world = new World();
        FluidColumnGrid grid = new FluidColumnGrid();
        grid.set(5, 5, openWater());
        world.water(6, LAKE_BLOCK, 5, LAKE);
        assertEquals(0, new CoveredWater().flood(grid, MIN_Y, 0, 0, world, (x, z) -> { }));
        assertEquals(0, grid.get(6, 5), "a column nobody has read stays unknown");
        assertEquals(0, shore(grid, 5, 5), "nothing known around the water: no foam at all");
    }
}
