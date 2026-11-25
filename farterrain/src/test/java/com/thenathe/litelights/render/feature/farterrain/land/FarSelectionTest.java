package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FarSelectionTest {
    /** Land seen everywhere within a square, every level stored, meshed where {@code meshed} says. */
    private static final class Land implements FarSelection.Tiles {
        final int radiusBlocks;
        final Set<Long> notMeshed = new HashSet<>();
        final Set<Long> notStored = new HashSet<>();
        boolean coarseStored = true;

        Land(int radiusBlocks) {
            this.radiusBlocks = radiusBlocks;
        }

        @Override
        public boolean present(long key) {
            int span = TileKey.span(TileKey.level(key));
            int x = TileKey.originX(key);
            int z = TileKey.originZ(key);
            return x + span > -radiusBlocks && x < radiusBlocks && z + span > -radiusBlocks && z < radiusBlocks;
        }

        @Override
        public boolean stored(long key) {
            return present(key) && !notStored.contains(key) && (coarseStored || TileKey.level(key) == 0);
        }

        @Override
        public boolean ready(long key) {
            return !notMeshed.contains(key);
        }

        @Override
        public int lowestY(long key) {
            return 60;
        }

        @Override
        public int highestY(long key) {
            return 70;
        }
    }

    private static final FarSelection.View EVERYTHING = (minX, minY, minZ, maxX, maxY, maxZ) -> true;

    private static Set<Long> drawn(FarSelection.Tiles tiles, double x, double z, float far, float levelDistance) {
        Set<Long> drawn = new HashSet<>();
        new FarSelection().select(tiles, EVERYTHING, x, 100, z, far, levelDistance, (key, visible) -> drawn.add(key), key -> { });
        return drawn;
    }

    /** Every block column within the land is under exactly one drawn tile. */
    private static void assertCoveredOnce(Set<Long> drawn, int radius, int step) {
        for (int z = -radius + step / 2; z < radius; z += step) {
            for (int x = -radius + step / 2; x < radius; x += step) {
                int covering = 0;
                for (long key : drawn) {
                    int span = TileKey.span(TileKey.level(key));
                    if (x >= TileKey.originX(key) && x < TileKey.originX(key) + span && z >= TileKey.originZ(key) && z < TileKey.originZ(key) + span) {
                        covering++;
                    }
                }
                assertEquals(1, covering, "column " + x + ", " + z);
            }
        }
    }

    @Test
    void aTileDrawnSplitStaysSplitWhileANewChildIsMeshed() {
        Land land = new Land(2048);
        FarSelection selection = new FarSelection();
        Set<Long> before = new HashSet<>();
        selection.select(land, EVERYTHING, 0, 100, 0, 4000, 128, (key, visible) -> before.add(key), key -> { });
        // A level-0 tile next to the camera is stored again as new land, not meshed yet.
        long fresh = TileKey.of(0, 1, 1);
        assertTrue(before.contains(fresh));
        land.notMeshed.add(fresh);
        Set<Long> after = new HashSet<>();
        selection.select(land, EVERYTHING, 0, 100, 0, 4000, 128, (key, visible) -> after.add(key), key -> { });
        for (long key : after) {
            assertTrue(before.contains(key), "nothing coarser takes over: " + TileKey.describe(key));
        }
        assertFalse(after.contains(fresh), "the new tile shows once meshed");
        // A selection with no history (a fresh session) draws the coarser tile instead, so no hole opens.
        Set<Long> fresh2 = new HashSet<>();
        new FarSelection().select(land, EVERYTHING, 0, 100, 0, 4000, 128, (key, visible) -> fresh2.add(key), key -> { });
        assertTrue(fresh2.contains(TileKey.parent(fresh)));
    }

    @Test
    void underAStandInEverythingThatWillBeDrawnIsWantedAtOnce() {
        Land land = new Land(2048);
        Set<Long> finished = drawn(land, 0, 0, 4000, 128);
        // Just after a jump: nothing below the top level is meshed yet.
        for (int level = 0; level < TileKey.MAX_LEVEL; level++) {
            for (int z = -2048 >> (5 + level); z < 2048 >> (5 + level); z++) {
                for (int x = -2048 >> (5 + level); x < 2048 >> (5 + level); x++) {
                    land.notMeshed.add(TileKey.of(level, x, z));
                }
            }
        }
        Set<Long> wanted = new HashSet<>();
        Set<Long> next = new HashSet<>();
        FarSelection selection = new FarSelection();
        selection.select(land, EVERYTHING, 0, 100, 0, 4000, 128, (key, visible) -> { }, (FarSelection.Wanted) (key, first) -> {
            wanted.add(key);
            if (first) {
                next.add(key);
            }
        });
        assertTrue(selection.standIns() > 0);
        for (long key : finished) {
            assertTrue(wanted.contains(key), "the tile the land ends up drawn with is wanted already: " + TileKey.describe(key));
        }
        // The top tiles stand in; their children, which they wait for to split, are the next step.
        assertTrue(!next.isEmpty());
        for (long key : next) {
            assertEquals(TileKey.MAX_LEVEL - 1, TileKey.level(key), TileKey.describe(key));
        }
    }

    @Test
    void landIsCoveredOnceAndGetsCoarserFarther() {
        Land land = new Land(2048);
        Set<Long> drawn = drawn(land, 0, 0, 4000, 128);
        assertCoveredOnce(drawn, 2048, 16);
        int nearLevel = Integer.MAX_VALUE;
        int farLevel = -1;
        for (long key : drawn) {
            int centre = TileKey.originX(key) + TileKey.span(TileKey.level(key)) / 2;
            int level = TileKey.level(key);
            if (Math.abs(centre) < 100 && Math.abs(TileKey.originZ(key)) < 100) {
                nearLevel = Math.min(nearLevel, level);
            }
            if (Math.abs(centre) > 1500) {
                farLevel = Math.max(farLevel, level);
            }
        }
        assertEquals(0, nearLevel);
        assertTrue(farLevel >= 3, "far land is level " + farLevel);
    }

    @Test
    void unmeshedChildrenLeaveTheParentDrawn() {
        Land land = new Land(1024);
        long child = TileKey.of(0, 1, 1);
        land.notMeshed.add(child);
        Set<Long> drawn = drawn(land, 40, 40, 2000, 128);
        assertFalse(drawn.contains(child));
        assertTrue(drawn.contains(TileKey.parent(child)), "the parent stands in");
        assertCoveredOnce(drawn, 1024, 16);
    }

    @Test
    void unmeshedParentLeavesItsChildrenDrawn() {
        Land land = new Land(1024);
        // Far from the camera a level-3 tile isn't meshed yet; its children are drawn, finer than needed.
        long parent = TileKey.of(3, 2, 0);
        land.notMeshed.add(parent);
        Set<Long> drawn = drawn(land, 0, 0, 2000, 128);
        assertFalse(drawn.contains(parent));
        assertCoveredOnce(drawn, 1024, 16);
    }

    @Test
    void coarseTilesNotBuiltYetLetTheirChildrenThrough() {
        Land land = new Land(512);
        land.coarseStored = false;
        Set<Long> drawn = drawn(land, 0, 0, 2000, 128);
        for (long key : drawn) {
            assertEquals(0, TileKey.level(key));
        }
        assertCoveredOnce(drawn, 512, 16);
    }

    @Test
    void nothingPastTheFarDistance() {
        Land land = new Land(8192);
        for (long key : drawn(land, 0, 0, 1000, 128)) {
            int span = TileKey.span(TileKey.level(key));
            double dx = Math.max(Math.max(TileKey.originX(key) - 0.0, 0.0 - (TileKey.originX(key) + span)), 0.0);
            double dz = Math.max(Math.max(TileKey.originZ(key) - 0.0, 0.0 - (TileKey.originZ(key) + span)), 0.0);
            assertTrue(Math.sqrt(dx * dx + dz * dz + 30 * 30) <= 1000.0, TileKey.describe(key));
        }
    }

    @Test
    void culledTilesAreWantedButNotDrawn() {
        Land land = new Land(1024);
        Set<Long> drawn = new HashSet<>();
        Set<Long> wanted = new HashSet<>();
        FarSelection.View eastOnly = (minX, minY, minZ, maxX, maxY, maxZ) -> maxX > 0;
        new FarSelection().select(land, eastOnly, 0, 100, 0, 2000, 128, (key, visible) -> {
            if (visible) {
                drawn.add(key);
            }
        }, wanted::add);
        assertTrue(wanted.size() > drawn.size());
        for (long key : drawn) {
            assertTrue(TileKey.originX(key) + TileKey.span(TileKey.level(key)) > 0);
        }
    }

    @Test
    void directionsFacingAwayAreLeftOut() {
        // Camera above and east of the box: up and east faces only, and those along the other axis.
        int bits = FarSelection.facingDirections(100, 200, 16, 0, 0, 0, 32, 100, 32);
        assertEquals(0, bits & 1 << FarQuad.DOWN);
        assertTrue((bits & 1 << FarQuad.UP) != 0);
        assertEquals(0, bits & 1 << FarQuad.WEST);
        assertTrue((bits & 1 << FarQuad.EAST) != 0);
        assertTrue((bits & 1 << FarQuad.NORTH) != 0 && (bits & 1 << FarQuad.SOUTH) != 0, "inside the box's z range");
    }
}
