package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarMesherTest {
    private final TestLooks looks = new TestLooks();
    private final FarMesher mesher = new FarMesher();

    private static EdgeTops neighboursAt(int top) {
        int[][] tops = new int[4][32];
        for (int[] edge : tops) {
            java.util.Arrays.fill(edge, top);
        }
        return new EdgeTops(tops);
    }

    @Test
    void flatLandIsOneQuadOnTopAndASkirtRowOnEachSide() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        FarMesh mesh = mesher.mesh(tile, neighboursAt(70), looks, true);
        assertEquals(1, mesh.quadCount()[FarQuad.UP], "the whole top merges into one quad");
        int up = mesh.firstQuad()[FarQuad.UP] * FarQuad.INTS;
        assertEquals(32, FarQuad.extentA(mesh.quads(), up));
        assertEquals(32, FarQuad.extentB(mesh.quads(), up));
        assertEquals(70, FarQuad.y(mesh.quads(), up));
        assertEquals(TestLooks.GRASS * 8 + FarQuad.UP + 1, FarQuad.appearance(mesh.quads(), up));
        assertEquals(15, FarQuad.skyLight(mesh.quads(), up));
        assertEquals(0, mesh.quadCount()[FarQuad.DOWN]);
        for (int face = FarQuad.NORTH; face <= FarQuad.EAST; face++) {
            assertEquals(1, mesh.quadCount()[face], "one skirt row of grass along each edge");
            int offset = mesh.firstQuad()[face] * FarQuad.INTS;
            assertEquals(70, FarQuad.y(mesh.quads(), offset));
            assertEquals(1, FarQuad.extentB(mesh.quads(), offset));
        }
    }

    @Test
    void lowerNeighbourShowsTheCliff() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        int[][] tops = new int[4][32];
        for (int[] edge : tops) {
            java.util.Arrays.fill(edge, 70);
        }
        java.util.Arrays.fill(tops[EdgeTops.EAST], 60);
        FarMesh mesh = mesher.mesh(tile, new EdgeTops(tops), looks, true);
        int rows = 0;
        for (int i = 0; i < mesh.quadCount()[FarQuad.EAST]; i++) {
            int offset = (mesh.firstQuad()[FarQuad.EAST] + i) * FarQuad.INTS;
            assertEquals(31, FarQuad.x(mesh.quads(), offset));
            rows += FarQuad.extentB(mesh.quads(), offset);
        }
        assertEquals(11, rows, "rows 60 to 70: the cliff and the skirt row");
    }

    @Test
    void unseenNeighbourGetsAWallOfLimitedDepth() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        FarMesh mesh = mesher.mesh(tile, EdgeTops.UNKNOWN, looks, true);
        int offset = mesh.firstQuad()[FarQuad.WEST] * FarQuad.INTS;
        int rows = 0;
        for (int i = 0; i < mesh.quadCount()[FarQuad.WEST]; i++) {
            rows += FarQuad.extentB(mesh.quads(), offset + i * FarQuad.INTS);
        }
        assertEquals(FarMesher.UNSEEN_WALL_BLOCKS, rows);
    }

    @Test
    void theEdgeTowardUnseenLandIsClosedDownPastItsLitCaves() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        // A lava-lit tunnel at rows 30 to 33 running out of the tile's west edge.
        for (int x = 0; x < 32; x++) {
            for (int y = 30; y <= 33; y++) {
                int index = TileVoxels.index(x, y, 10);
                tile.states[index] = TestLooks.AIR;
                tile.light[index] = 12;
            }
        }
        FarMesh mesh = mesher.mesh(tile, EdgeTops.UNKNOWN, looks, true);
        boolean[] covered = new boolean[128];
        for (int i = 0; i < mesh.quadCount()[FarQuad.WEST]; i++) {
            int offset = (mesh.firstQuad()[FarQuad.WEST] + i) * FarQuad.INTS;
            int z = FarQuad.z(mesh.quads(), offset);
            if (FarQuad.x(mesh.quads(), offset) == 0 && z <= 10 && 10 < z + FarQuad.extentA(mesh.quads(), offset)) {
                for (int y = FarQuad.y(mesh.quads(), offset); y < FarQuad.y(mesh.quads(), offset) + FarQuad.extentB(mesh.quads(), offset); y++) {
                    covered[y] = true;
                }
            }
        }
        for (int y = 29; y <= 70; y++) {
            assertTrue(covered[y], "row " + y + " of the edge is closed, the tunnel's mouth included");
        }
        assertTrue(!covered[28], "nothing below the lowest lit cave");
        FarMesh noCave = mesher.mesh(TestLooks.flatLand(128, 70), EdgeTops.UNKNOWN, looks, true);
        int wallRows = 0;
        for (int i = 0; i < noCave.quadCount()[FarQuad.WEST]; i++) {
            wallRows += FarQuad.extentB(noCave.quads(), (noCave.firstQuad()[FarQuad.WEST] + i) * FarQuad.INTS);
        }
        assertEquals(FarMesher.UNSEEN_WALL_BLOCKS, wallRows, "with no lit cave, the wall alone");
    }

    @Test
    void closedCavesAreLeftOutOpenOnesKept() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        // A dark cave and, beside it, one lit by a torch.
        for (int x = 4; x < 8; x++) {
            tile.states[TileVoxels.index(x, 30, 10)] = TestLooks.AIR;
            tile.states[TileVoxels.index(x, 30, 20)] = TestLooks.AIR;
            tile.light[TileVoxels.index(x, 30, 20)] = 12;
        }
        FarMesh culled = mesher.mesh(tile, neighboursAt(70), looks, true);
        FarMesh all = mesher.mesh(tile, neighboursAt(70), looks, false);
        int plain = mesher.mesh(TestLooks.flatLand(128, 70), neighboursAt(70), looks, true).totalQuads();
        assertTrue(all.totalQuads() > culled.totalQuads(), "the dark cave's faces go");
        assertTrue(culled.totalQuads() > plain, "the lit cave's faces stay");
    }

    @Test
    void differentTintsDontMerge() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        for (int z = 0; z < 32; z++) {
            for (int x = 16; x < 32; x++) {
                tile.biomes[TileVoxels.column(x, z)] = 3;
            }
        }
        FarMesh mesh = mesher.mesh(tile, neighboursAt(70), looks, true);
        assertEquals(2, mesh.quadCount()[FarQuad.UP]);
    }

    @Test
    void waterShowsOnlyAtItsSurface() {
        TileVoxels tile = TestLooks.flatLand(128, 50);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                for (int y = 51; y <= 62; y++) {
                    int index = TileVoxels.index(x, y, z);
                    tile.states[index] = TestLooks.WATER;
                    tile.light[index] = 0;
                }
            }
        }
        FarMesh mesh = mesher.mesh(tile, neighboursAt(62), looks, true);
        assertEquals(1, mesh.quadCount()[FarMesh.WATER], "one top, in the water's own bucket, and no skirt under it");
        int up = mesh.firstQuad()[FarMesh.WATER] * FarQuad.INTS;
        assertEquals(FarQuad.UP, FarQuad.face(mesh.quads(), up));
        assertEquals(62, FarQuad.y(mesh.quads(), up));
        assertEquals(TestLooks.WATER * 8 + FarQuad.UP + 1, FarQuad.appearance(mesh.quads(), up));
    }

    @Test
    void waterTopsCarryTheirDepthAndSplitOnlyWhereItsClassChanges() {
        // A floor sloping down eastward: 3 blocks of water over x 0 to 9, 4 over 10 to 19, 12 over the rest.
        TileVoxels tile = TestLooks.flatLand(128, 50);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int floor = x < 10 ? 59 : x < 20 ? 58 : 50;
                for (int y = floor + 1; y <= 62; y++) {
                    int index = TileVoxels.index(x, y, z);
                    tile.states[index] = TestLooks.WATER;
                    tile.light[index] = (byte) 0xF0;
                }
                for (int y = 51; y <= floor; y++) {
                    tile.states[TileVoxels.index(x, y, z)] = tile.states[TileVoxels.index(x, 50, z)];
                }
            }
        }
        FarMesh mesh = mesher.mesh(tile, neighboursAt(62), looks, true);
        int[] quads = mesh.quads();
        int tops = 0;
        for (int quad = mesh.firstQuad()[FarMesh.WATER]; quad < mesh.firstQuad()[FarMesh.WATER] + mesh.quadCount()[FarMesh.WATER]; quad++) {
            int at = quad * FarQuad.INTS;
            if (FarQuad.face(quads, at) != FarQuad.UP) {
                continue;
            }
            tops++;
            int depth = FarQuad.WATER_DEPTHS[(quads[at + 1] >>> 23) & 0xF];
            int x = FarQuad.x(quads, at);
            assertEquals(x < 10 ? 3 : x < 20 ? 4 : 12, depth, "the water's depth at x " + x);
        }
        assertEquals(3, tops, "one top for each depth");
    }

    @Test
    void depthClassesRoundToTheNearestStep() {
        assertEquals(1, FarQuad.WATER_DEPTHS[FarQuad.depthClass(1)]);
        assertEquals(6, FarQuad.WATER_DEPTHS[FarQuad.depthClass(6)]);
        assertEquals(8, FarQuad.WATER_DEPTHS[FarQuad.depthClass(7)], "a tie goes deeper");
        assertEquals(12, FarQuad.WATER_DEPTHS[FarQuad.depthClass(13)]);
        assertEquals(64, FarQuad.WATER_DEPTHS[FarQuad.depthClass(200)]);
        assertEquals(64, FarQuad.WATER_DEPTHS[0], "no class given: deep");
    }

    @Test
    void theFloorUnderLitWaterIsDrawnUnderDarkWaterNot() {
        TileVoxels tile = TestLooks.flatLand(128, 50);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                for (int y = 51; y <= 54; y++) {
                    int index = TileVoxels.index(x, y, z);
                    tile.states[index] = TestLooks.WATER;
                    // Shallow water over the west half, lit; deep and dark over the east half.
                    tile.light[index] = x < 16 ? (byte) 0xB0 : 0;
                }
            }
        }
        FarMesh mesh = mesher.mesh(tile, neighboursAt(54), looks, true);
        assertEquals(1, mesh.quadCount()[FarQuad.UP], "the lit half of the floor, one quad");
        int floor = mesh.firstQuad()[FarQuad.UP] * FarQuad.INTS;
        assertEquals(50, FarQuad.y(mesh.quads(), floor));
        assertEquals(16, FarQuad.extentA(mesh.quads(), floor));
        assertEquals(11, FarQuad.skyLight(mesh.quads(), floor), "lit as the water over it");
    }

    // --- Full detail: blocks in their own shapes, and plants ---

    @Test
    void aRowOfSlabTopsIsOneQuadAtHalfHeight() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        for (int x = 4; x < 12; x++) {
            tile.states[TileVoxels.index(x, 71, 10)] = TestLooks.SLAB;
        }
        FarMesh mesh = mesher.mesh(tile, neighboursAt(70), looks, true);
        int slabTops = 0;
        for (int i = 0; i < mesh.quadCount()[FarQuad.UP]; i++) {
            int offset = (mesh.firstQuad()[FarQuad.UP] + i) * FarQuad.INTS;
            if (FarQuad.detail(mesh.quads(), offset)) {
                slabTops++;
                assertEquals(71, FarQuad.y(mesh.quads(), offset));
                assertEquals(8, FarQuad.extentA(mesh.quads(), offset), "eight slab tops merged along the row");
                assertEquals(FarQuad.detailPart(0, 16, 0, 16, 8), FarQuad.part(mesh.quads(), offset), "half a block in from the top");
            }
        }
        assertEquals(1, slabTops);
    }

    @Test
    void aSlabHidesOnlyTheFaceItCovers() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        // A stone pillar with a slab beside it: the pillar's face toward the slab still shows above
        // the slab's half; the grass under the slab is hidden by its full bottom.
        tile.states[TileVoxels.index(10, 71, 10)] = TestLooks.STONE;
        tile.light[TileVoxels.index(10, 71, 10)] = 0;
        tile.states[TileVoxels.index(11, 71, 10)] = TestLooks.SLAB;
        FarMesh mesh = mesher.mesh(tile, neighboursAt(70), looks, true);
        boolean pillarEastFace = false;
        for (int i = 0; i < mesh.quadCount()[FarQuad.EAST]; i++) {
            int offset = (mesh.firstQuad()[FarQuad.EAST] + i) * FarQuad.INTS;
            if (!FarQuad.detail(mesh.quads(), offset) && FarQuad.x(mesh.quads(), offset) == 10 && FarQuad.y(mesh.quads(), offset) == 71) {
                pillarEastFace = true;
            }
        }
        assertTrue(pillarEastFace, "the pillar's face beside the slab is drawn");
        FarMesh flat = mesher.mesh(TestLooks.flatLand(128, 70), neighboursAt(70), looks, true);
        assertEquals(1, flat.quadCount()[FarQuad.UP]);
        for (int i = 0; i < mesh.quadCount()[FarQuad.UP]; i++) {
            int offset = (mesh.firstQuad()[FarQuad.UP] + i) * FarQuad.INTS;
            if (!FarQuad.detail(mesh.quads(), offset) && FarQuad.y(mesh.quads(), offset) == 70) {
                int x = FarQuad.x(mesh.quads(), offset);
                int z = FarQuad.z(mesh.quads(), offset);
                boolean coversSlabColumn = x <= 11 && 11 < x + FarQuad.extentA(mesh.quads(), offset)
                    && z <= 10 && 10 < z + FarQuad.extentB(mesh.quads(), offset);
                assertTrue(!coversSlabColumn, "no grass top drawn under the slab");
            }
        }
    }

    @Test
    void fencePostsStayApartAndPlantsAreCrossedCards() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        for (int x = 4; x < 8; x++) {
            tile.states[TileVoxels.index(x, 71, 10)] = TestLooks.FENCE;
        }
        tile.states[TileVoxels.index(20, 71, 20)] = TestLooks.PLANT;
        FarMesh mesh = mesher.mesh(tile, neighboursAt(70), looks, true);
        int postTops = 0;
        for (int i = 0; i < mesh.quadCount()[FarQuad.UP]; i++) {
            int offset = (mesh.firstQuad()[FarQuad.UP] + i) * FarQuad.INTS;
            if (FarQuad.detail(mesh.quads(), offset)) {
                postTops++;
                assertEquals(1, FarQuad.extentA(mesh.quads(), offset), "a post's top covers part of its block, so it can't merge");
            }
        }
        assertEquals(4, postTops);
        assertEquals(2, mesh.quadCount()[FarMesh.CARDS], "one plant, two cards");
        int card = mesh.firstQuad()[FarMesh.CARDS] * FarQuad.INTS;
        assertEquals(FarQuad.CARD, FarQuad.face(mesh.quads(), card));
        assertEquals(FarQuad.CARD_CROSSED, FarQuad.face(mesh.quads(), card + FarQuad.INTS));
        assertEquals(TestLooks.PLANT * 8 + FarQuad.CARD + 1, FarQuad.appearance(mesh.quads(), card));
        assertEquals(71, mesh.highestRow(), "the posts and the plant count for the tile's height");
    }

    @Test
    void coarseLevelsDrawWholeVoxelsOnly() {
        TileVoxels tile = new TileVoxels(1, -64, 64);
        for (int z = 0; z < TileVoxels.SIZE; z++) {
            for (int x = 0; x < TileVoxels.SIZE; x++) {
                tile.setKnown(x, z);
                for (int y = 0; y < 64; y++) {
                    int index = TileVoxels.index(x, y, z);
                    tile.states[index] = y < 30 ? TestLooks.STONE : y == 30 ? TestLooks.SLAB : y == 31 ? TestLooks.PLANT : 0;
                    tile.light[index] = y > 30 ? (byte) 0xF0 : 0;
                }
            }
        }
        FarMesh mesh = mesher.mesh(tile, neighboursAt(30), looks, true);
        assertEquals(0, mesh.quadCount()[FarMesh.CARDS]);
        for (int i = 0; i < mesh.totalQuads(); i++) {
            assertTrue(!FarQuad.detail(mesh.quads(), i * FarQuad.INTS));
        }
        assertEquals(1, mesh.quadCount()[FarQuad.UP], "the slab is a whole voxel at level 1");
    }

    /** A coarse tile of grass-topped stone: each column's top row and how far below that row's top its land ends. */
    private static TileVoxels coarseLand(int level, int[] tops, int[] insets) {
        TileVoxels tile = new TileVoxels(level, -64, 48);
        for (int column = 0; column < TileVoxels.COLUMNS; column++) {
            int x = column % TileVoxels.SIZE;
            int z = column / TileVoxels.SIZE;
            tile.setKnown(x, z);
            for (int y = 0; y < tile.height; y++) {
                int index = TileVoxels.index(x, y, z);
                if (y <= tops[column]) {
                    tile.states[index] = y == tops[column] ? TestLooks.GRASS : TestLooks.STONE;
                    tile.light[index] = (byte) (y == tops[column] ? insets[column] : 0);
                } else {
                    tile.light[index] = (byte) 0xF0;
                }
            }
        }
        return tile;
    }

    /** Where a column's land ends, in blocks above the tile's bottom. */
    private static int surface(int level, int top, int inset) {
        return ((top + 1) << level) - inset;
    }

    @Test
    void aCoarseSlopeWithinOneRowOfVoxelsHasItsSteps() {
        int[] tops = new int[TileVoxels.COLUMNS];
        int[] insets = new int[TileVoxels.COLUMNS];
        java.util.Arrays.fill(tops, 20);
        for (int column = 0; column < TileVoxels.COLUMNS; column++) {
            // Four strips west to east, all their tops in row 20, each ending 4 blocks lower.
            insets[column] = column % TileVoxels.SIZE / 8 * 4;
        }
        FarMesh mesh = mesher.mesh(coarseLand(4, tops, insets), neighboursAt(20), looks, true);
        int steps = 0;
        for (int i = 0; i < mesh.quadCount()[FarQuad.EAST]; i++) {
            int offset = (mesh.firstQuad()[FarQuad.EAST] + i) * FarQuad.INTS;
            int x = FarQuad.x(mesh.quads(), offset);
            if (x < 31) {
                assertTrue(x == 7 || x == 15 || x == 23, "a step only where the land drops, at " + x);
                assertEquals(20, FarQuad.y(mesh.quads(), offset));
                assertEquals(x / 8 * 4, FarQuad.inset(mesh.quads(), offset), "its top edge where its own land ends");
                steps++;
            }
        }
        assertEquals(3, steps, "one step between each two strips, the strip's whole length");
        for (int i = 0; i < mesh.quadCount()[FarQuad.WEST]; i++) {
            assertEquals(0, FarQuad.x(mesh.quads(), (mesh.firstQuad()[FarQuad.WEST] + i) * FarQuad.INTS), "no side toward higher land");
        }
    }

    @Test
    void everyStepBetweenCoarseColumnsIsClosed() {
        java.util.Random random = new java.util.Random(26_03L);
        for (int level = 1; level <= 7; level++) {
            int size = 1 << level;
            int[] tops = new int[TileVoxels.COLUMNS];
            int[] insets = new int[TileVoxels.COLUMNS];
            for (int column = 0; column < TileVoxels.COLUMNS; column++) {
                tops[column] = 20 + random.nextInt(3);
                insets[column] = random.nextInt(size);
            }
            FarMesh mesh = mesher.mesh(coarseLand(level, tops, insets), neighboursAt(20), looks, true);
            // Per column and side, the blocks its side faces cover, from the tile's bottom.
            boolean[][][] covered = new boolean[TileVoxels.COLUMNS][6][(48 << level) + 1];
            for (int face = FarQuad.NORTH; face <= FarQuad.EAST; face++) {
                for (int i = 0; i < mesh.quadCount()[face]; i++) {
                    int offset = (mesh.firstQuad()[face] + i) * FarQuad.INTS;
                    int x = FarQuad.x(mesh.quads(), offset);
                    int z = FarQuad.z(mesh.quads(), offset);
                    int bottom = FarQuad.y(mesh.quads(), offset) << level;
                    int top = ((FarQuad.y(mesh.quads(), offset) + FarQuad.extentB(mesh.quads(), offset)) << level) - FarQuad.inset(mesh.quads(), offset);
                    for (int along = 0; along < FarQuad.extentA(mesh.quads(), offset); along++) {
                        int column = face == FarQuad.NORTH || face == FarQuad.SOUTH ? TileVoxels.column(x + along, z) : TileVoxels.column(x, z + along);
                        for (int block = bottom; block < top; block++) {
                            covered[column][face][block] = true;
                        }
                    }
                }
            }
            for (int z = 0; z < TileVoxels.SIZE; z++) {
                for (int x = 0; x + 1 < TileVoxels.SIZE; x++) {
                    int west = TileVoxels.column(x, z);
                    int east = TileVoxels.column(x + 1, z);
                    int westTop = surface(level, tops[west], insets[west]);
                    int eastTop = surface(level, tops[east], insets[east]);
                    int high = westTop > eastTop ? west : east;
                    int face = westTop > eastTop ? FarQuad.EAST : FarQuad.WEST;
                    for (int block = Math.min(westTop, eastTop); block < Math.max(westTop, eastTop); block++) {
                        assertTrue(covered[high][face][block], "level " + level + ": the step at " + x + ", " + z + " is closed at block " + block);
                    }
                }
            }
        }
    }

    @Test
    void lightsGatherIntoOnePointACell() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        // Three torches close together, and one apart.
        tile.states[TileVoxels.index(1, 71, 1)] = TestLooks.TORCH;
        tile.states[TileVoxels.index(2, 71, 1)] = TestLooks.TORCH;
        tile.states[TileVoxels.index(1, 71, 2)] = TestLooks.TORCH;
        tile.states[TileVoxels.index(20, 71, 20)] = TestLooks.TORCH;
        FarMesh mesh = mesher.mesh(tile, neighboursAt(70), looks, true);
        assertEquals(2, mesh.quadCount()[FarMesh.LIGHTS]);
        int first = mesh.firstQuad()[FarMesh.LIGHTS] * FarQuad.INTS;
        int second = first + FarQuad.INTS;
        assertEquals(71, FarQuad.y(mesh.quads(), first));
        assertTrue(FarQuad.lightStrength(mesh.quads(), first) > FarQuad.lightStrength(mesh.quads(), second), "three lights glow stronger than one");
        assertEquals(0xFFB46A, FarQuad.tint(mesh.quads(), second));
    }

    @Test
    void noLightPointsFromBelowTheSeaLevel() {
        // Ground at row 70 (y 6), torches on it at y 7: under a sea at 63 they would glow through the water.
        TileVoxels tile = TestLooks.flatLand(160, 70);
        tile.states[TileVoxels.index(1, 71, 1)] = TestLooks.TORCH;
        tile.states[TileVoxels.index(20, 140, 20)] = TestLooks.TORCH;
        FarMesh everything = mesher.mesh(tile, neighboursAt(70), looks, true);
        assertEquals(2, everything.quadCount()[FarMesh.LIGHTS], "no floor: both lights");
        FarMesh underSea = mesher.mesh(tile, neighboursAt(70), looks, true, 63);
        assertEquals(1, underSea.quadCount()[FarMesh.LIGHTS], "the light at y 7 is under the sea level");
        assertEquals(140, FarQuad.y(underSea.quads(), underSea.firstQuad()[FarMesh.LIGHTS] * FarQuad.INTS), "the light at y 76 still shows");
        assertEquals(2, mesher.mesh(tile, neighboursAt(70), looks, true, 7).quadCount()[FarMesh.LIGHTS], "a light at the sea level itself shows");
    }

    @Test
    void aCoarseVoxelLitByBlocksIsBelowTheSeaLevelByItsTop() {
        TileVoxels tile = new TileVoxels(2, -64, 32);
        for (int z = 0; z < TileVoxels.SIZE; z++) {
            for (int x = 0; x < TileVoxels.SIZE; x++) {
                tile.setKnown(x, z);
                for (int y = 0; y < 32; y++) {
                    int index = TileVoxels.index(x, y, z);
                    tile.states[index] = y < 20 ? TestLooks.STONE : 0;
                    tile.light[index] = y < 20 ? 0 : (byte) 0xF0;
                }
            }
        }
        // Row 20 of four-block voxels holds y 16 to 19.
        tile.light[TileVoxels.index(5, 20, 5)] = (byte) 0xFE;
        assertEquals(1, mesher.mesh(tile, neighboursAt(19), looks, true, 19).quadCount()[FarMesh.LIGHTS]);
        assertEquals(0, mesher.mesh(tile, neighboursAt(19), looks, true, 20).quadCount()[FarMesh.LIGHTS]);
    }

    @Test
    void coarseLevelsFindLightsByTheLightTheySpread() {
        TileVoxels tile = new TileVoxels(2, -64, 32);
        for (int z = 0; z < TileVoxels.SIZE; z++) {
            for (int x = 0; x < TileVoxels.SIZE; x++) {
                tile.setKnown(x, z);
                for (int y = 0; y < 32; y++) {
                    int index = TileVoxels.index(x, y, z);
                    tile.states[index] = y < 20 ? TestLooks.STONE : 0;
                    tile.light[index] = y < 20 ? 0 : (byte) 0xF0;
                }
            }
        }
        // A torch was here: the coarse air over it keeps its block light at its brightest.
        tile.light[TileVoxels.index(5, 20, 5)] = (byte) 0xFE;
        FarMesh mesh = mesher.mesh(tile, neighboursAt(19), looks, true);
        assertEquals(1, mesh.quadCount()[FarMesh.LIGHTS]);
        int point = mesh.firstQuad()[FarMesh.LIGHTS] * FarQuad.INTS;
        assertEquals(5, FarQuad.x(mesh.quads(), point));
        assertEquals(20, FarQuad.y(mesh.quads(), point));
    }

    @Test
    void detailQuadsKeepTheirPlaceWithinTheBlock() {
        int[] quad = new int[4];
        int part = FarQuad.detailPart(6, 10, 0, 16, 3);
        FarQuad.writeDetail(quad, 0, 31, 1023, 17, FarQuad.SOUTH, 32, 32, 0xA7, 65535, 0x123456, part);
        FarQuad.setNode(quad, 0, 0xABCDEF);
        assertTrue(FarQuad.detail(quad, 0));
        assertEquals(31, FarQuad.x(quad, 0));
        assertEquals(1023, FarQuad.y(quad, 0));
        assertEquals(17, FarQuad.z(quad, 0));
        assertEquals(FarQuad.SOUTH, FarQuad.face(quad, 0));
        assertEquals(32, FarQuad.extentA(quad, 0));
        assertEquals(32, FarQuad.extentB(quad, 0));
        assertEquals(part, FarQuad.part(quad, 0));
        assertEquals(0, FarQuad.inset(quad, 0));
        assertEquals(10, FarQuad.skyLight(quad, 0));
        assertEquals(7, FarQuad.blockLight(quad, 0));
        assertEquals(0x123456, FarQuad.tint(quad, 0));
        assertEquals(0xABCDEF, FarQuad.node(quad, 0));
        assertTrue(!FarQuad.partSpansA(part) && FarQuad.partSpansB(part));
    }

    @Test
    void boxFacesLieWhereTheBoxIs() {
        int[] stairStep = {0, 8, 8, 16, 16, 16};
        assertEquals(FarQuad.detailPart(0, 16, 8, 16, 0), FarMesher.boxPart(stairStep, 0, FarQuad.UP));
        assertEquals(FarQuad.detailPart(0, 16, 8, 16, 8), FarMesher.boxPart(stairStep, 0, FarQuad.NORTH), "its front, half a block in");
        assertEquals(FarQuad.detailPart(8, 16, 8, 16, 0), FarMesher.boxPart(stairStep, 0, FarQuad.WEST));
    }

    @Test
    void nodeIndexFitsBesideTheRest() {
        int[] quad = new int[4];
        FarQuad.write(quad, 0, 31, 1023, 17, FarQuad.EAST, 32, 1024, 0xA7, 65535, 0x123456, 127);
        FarQuad.setNode(quad, 0, 0xABCDEF);
        assertEquals(31, FarQuad.x(quad, 0));
        assertEquals(1023, FarQuad.y(quad, 0));
        assertEquals(17, FarQuad.z(quad, 0));
        assertEquals(FarQuad.EAST, FarQuad.face(quad, 0));
        assertEquals(32, FarQuad.extentA(quad, 0));
        assertEquals(1024, FarQuad.extentB(quad, 0));
        assertEquals(10, FarQuad.skyLight(quad, 0));
        assertEquals(7, FarQuad.blockLight(quad, 0));
        assertEquals(65535, FarQuad.appearance(quad, 0));
        assertEquals(0x123456, FarQuad.tint(quad, 0));
        assertEquals(0xABCDEF, FarQuad.node(quad, 0));
        assertEquals(127, FarQuad.inset(quad, 0));
    }
}
