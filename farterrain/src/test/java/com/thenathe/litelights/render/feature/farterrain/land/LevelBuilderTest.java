package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LevelBuilderTest {
    private final TestLooks looks = new TestLooks();

    private static TileVoxels[] four(TileVoxels tile) {
        return new TileVoxels[] {tile, tile, tile, tile};
    }

    @Test
    void hillsideReadsAsGrassNotTheDirtUnder() {
        TileVoxels child = TestLooks.flatLand(64, 10);
        // Grass at row 10 over two rows of dirt. Parent row 5 (child rows 10 and 11) holds grass
        // and the air over it; parent row 4 (child rows 8 and 9), dirt alone.
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                child.states[TileVoxels.index(x, 8, z)] = TestLooks.DIRT;
                child.states[TileVoxels.index(x, 9, z)] = TestLooks.DIRT;
            }
        }
        TileVoxels parent = LevelBuilder.build(four(child), 1, -64, 64, looks);
        assertEquals(TestLooks.GRASS, parent.state(7, 5, 7));
        assertEquals(TestLooks.DIRT, parent.state(7, 4, 7));
        assertEquals(TestLooks.STONE, parent.state(7, 3, 7));
    }

    @Test
    void anySolidCellKeepsTheGroupSolid() {
        TileVoxels child = new TileVoxels(0, 0, 64);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                child.setKnown(x, z);
            }
        }
        child.states[TileVoxels.index(5, 21, 7)] = TestLooks.LEAVES;
        TileVoxels parent = LevelBuilder.build(new TileVoxels[] {child, null, null, null}, 1, 0, 64, looks);
        assertEquals(TestLooks.LEAVES, parent.state(2, 10, 3));
        assertEquals(TestLooks.AIR, parent.state(3, 10, 3));
    }

    @Test
    void quadrantsLandInTheirCorner() {
        TileVoxels low = TestLooks.flatLand(64, 4);
        TileVoxels high = TestLooks.flatLand(64, 40);
        TileVoxels parent = LevelBuilder.build(new TileVoxels[] {low, high, null, low}, 1, -64, 64, looks);
        assertEquals(TestLooks.GRASS, parent.state(0, 2, 0));
        assertEquals(TestLooks.GRASS, parent.state(20, 20, 0), "the east quadrant is the high land");
        assertFalse(parent.known(5, 20), "the south-west quadrant had nothing seen");
        assertTrue(parent.known(20, 20));
        assertEquals(2, parent.top(0, 0, looks), "the low land's grass, at child row 4, is parent row 2");
    }

    @Test
    void blockLightKeepsItsBrightestSkyAverages() {
        TileVoxels child = new TileVoxels(0, 0, 4);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                child.setKnown(x, z);
            }
        }
        // The group at parent (0, 0, 0): seven air cells in the dark, one beside a torch in daylight.
        child.light[TileVoxels.index(1, 1, 1)] = (byte) (15 << 4 | 14);
        TileVoxels parent = LevelBuilder.build(new TileVoxels[] {child, null, null, null}, 1, 0, 4, looks);
        int index = TileVoxels.index(0, 0, 0);
        assertEquals(14, parent.blockLight(index));
        assertEquals(2, parent.skyLight(index), "15 / 8, rounded");
    }

    @Test
    void coarseVoxelsKnowWhereTheirLandEnds() {
        // Grass at row 70 (blocks 70 and up of a level-0 tile): level 1's voxel 35 holds rows 70
        // and 71, full to one block of two; level 2's voxel 17 holds rows 68 to 71, full to three of four.
        TileVoxels child = TestLooks.flatLand(128, 70);
        TileVoxels level1 = LevelBuilder.build(new TileVoxels[] {child, child, child, child}, 1, -64, 128, looks);
        assertEquals(TestLooks.GRASS, level1.state(3, 35, 3));
        assertEquals(1, TileVoxels.inset(level1, TileVoxels.index(3, 35, 3)));
        assertEquals(0, TileVoxels.inset(level1, TileVoxels.index(3, 34, 3)), "a full voxel under it");
        TileVoxels level2 = LevelBuilder.build(new TileVoxels[] {level1, level1, level1, level1}, 2, -64, 128, looks);
        assertEquals(1, TileVoxels.inset(level2, TileVoxels.index(3, 17, 3)));
        assertEquals(0, TileVoxels.inset(child, TileVoxels.index(3, 70, 3)), "level 0 has none");
    }

    @Test
    void heightHalvesEachLevel() {
        assertEquals(384, TileVoxels.heightFor(384, 0));
        assertEquals(192, TileVoxels.heightFor(384, 1));
        assertEquals(3, TileVoxels.heightFor(384, 7));
        assertEquals(1, TileVoxels.heightFor(100, 7));
    }

    @Test
    void winnerPrefersExposureThenCommonness() {
        assertEquals(7, LevelBuilder.winner(new int[] {5, 7, 5}, new int[] {2, 9, 2}, 3));
        assertEquals(5, LevelBuilder.winner(new int[] {7, 5, 5}, new int[] {3, 3, 3}, 3));
    }
}
