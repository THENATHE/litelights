package com.thenathe.litelights.render.feature.farterrain.land;

/**
 * Builds a tile one level up from the four tiles under it: each 2 by 2 by 2 group of voxels becomes
 * one. The rule keeps what a player sees from far away:
 * <ul>
 *   <li>the group is solid if any of its cells is (surfaces stay closed: no holes appear);</li>
 *   <li>it takes the block of the cell most open to the air, the air above counting most, so a
 *   hillside reads as grass rather than the dirt under it; ties go to the block most of the group
 *   is made of;</li>
 *   <li>its light is the air cells' sky light on average and their block light at its brightest,
 *   so a lit village stays lit from far away instead of averaging out;</li>
 *   <li>a column is seen if any of the four under it was, and takes the biome of the highest;</li>
 *   <li>a solid voxel notes how far below its top its land really ends ({@link TileVoxels#inset}),
 *   so its top is drawn where the land is, not up to a whole voxel higher.</li>
 * </ul>
 */
public final class LevelBuilder {
    private static final int UP_SCORE = 8;
    private static final int SIDE_SCORE = 2;
    private static final int DOWN_SCORE = 1;

    private LevelBuilder() {
    }

    /**
     * The tile over these four (quadrant 0 to 3, x in bit 0 and z in bit 1; null for a quadrant
     * with nothing seen). They must all be one level, below the one built.
     */
    public static TileVoxels build(TileVoxels[] children, int level, int minY, int worldHeight, BlockKinds kinds) {
        TileVoxels parent = new TileVoxels(level, minY, TileVoxels.heightFor(worldHeight, level));
        int[] cellStates = new int[8];
        int[] cellScores = new int[8];
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            TileVoxels child = children[quadrant];
            if (child == null) {
                continue;
            }
            byte[] childKinds = child.kinds(kinds);
            int offsetX = (quadrant & 1) * (TileVoxels.SIZE / 2);
            int offsetZ = (quadrant >> 1) * (TileVoxels.SIZE / 2);
            for (int pz = 0; pz < TileVoxels.SIZE / 2; pz++) {
                for (int px = 0; px < TileVoxels.SIZE / 2; px++) {
                    buildColumn(parent, child, childKinds, offsetX + px, offsetZ + pz, px * 2, pz * 2, cellStates, cellScores);
                }
            }
        }
        return parent;
    }

    private static void buildColumn(TileVoxels parent, TileVoxels child, byte[] kinds, int x, int z, int childX, int childZ,
                                    int[] cellStates, int[] cellScores) {
        boolean known = false;
        int highestTop = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            int cx = childX + (i & 1);
            int cz = childZ + (i >> 1);
            if (!child.known(cx, cz)) {
                continue;
            }
            known = true;
            int top = top(child, kinds, cx, cz);
            if (top > highestTop) {
                highestTop = top;
                parent.biomes[TileVoxels.column(x, z)] = child.biomes[TileVoxels.column(cx, cz)];
            }
        }
        if (!known) {
            return;
        }
        parent.setKnown(x, z);
        int childSize = 1 << child.level;
        for (int y = 0; y < parent.height; y++) {
            int solidCells = 0;
            int airCells = 0;
            int skySum = 0;
            int blockMax = 0;
            int filled = 0;
            for (int cell = 0; cell < 8; cell++) {
                int cx = childX + (cell & 1);
                int cz = childZ + ((cell >> 1) & 1);
                int cy = y * 2 + (cell >> 2);
                if (cy >= child.height) {
                    // Above the world: open air, as far as light goes.
                    airCells++;
                    skySum += 15;
                    continue;
                }
                int index = TileVoxels.index(cx, cy, cz);
                int state = child.states[index];
                if (kinds[index] == BlockKinds.AIR) {
                    airCells++;
                    skySum += child.skyLight(index);
                    blockMax = Math.max(blockMax, child.blockLight(index));
                } else {
                    cellStates[solidCells] = state;
                    solidCells++;
                    int cellTop = (cell >> 2) * childSize + childSize - TileVoxels.inset(child, index);
                    filled = Math.max(filled, cellTop);
                }
            }
            int index = TileVoxels.index(x, y, z);
            if (solidCells > 0) {
                parent.states[index] = sameState(cellStates, solidCells) ? cellStates[0]
                    : winner(child, kinds, childX, y * 2, childZ, cellStates, cellScores, solidCells);
            }
            if (solidCells > 0) {
                // A solid voxel's own light means nothing (faces take the air's), so a coarse one
                // keeps there how far below its top the land in it really ends (TileVoxels.inset).
                parent.light[index] = (byte) (childSize * 2 - filled);
            } else if (airCells > 0) {
                int sky = (skySum + airCells / 2) / airCells;
                parent.light[index] = (byte) (sky << 4 | blockMax);
            }
        }
    }

    private static int top(TileVoxels tile, byte[] kinds, int x, int z) {
        for (int y = tile.height - 1; y >= 0; y--) {
            if (kinds[TileVoxels.index(x, y, z)] != BlockKinds.AIR) {
                return y;
            }
        }
        return TileVoxels.EMPTY_TOP;
    }

    private static boolean sameState(int[] states, int count) {
        for (int i = 1; i < count; i++) {
            if (states[i] != states[0]) {
                return false;
            }
        }
        return true;
    }

    /** The winner of a group of mixed blocks, each solid cell scored by how open it is. */
    private static int winner(TileVoxels child, byte[] kinds, int childX, int childY, int childZ, int[] cellStates, int[] cellScores,
                              int solidCells) {
        int solid = 0;
        for (int cell = 0; cell < 8 && solid < solidCells; cell++) {
            int cx = childX + (cell & 1);
            int cz = childZ + ((cell >> 1) & 1);
            int cy = childY + (cell >> 2);
            if (cy >= child.height || kinds[TileVoxels.index(cx, cy, cz)] == BlockKinds.AIR) {
                continue;
            }
            cellScores[solid++] = exposure(child, cx, cy, cz, kinds);
        }
        return winner(cellStates, cellScores, solidCells);
    }

    /** How open a cell is to the air: above counts most, then each side, then below. */
    private static int exposure(TileVoxels tile, int x, int y, int z, byte[] kinds) {
        int score = 0;
        if (y + 1 >= tile.height || isAir(tile, x, y + 1, z, kinds)) {
            score += UP_SCORE;
        }
        if (y > 0 && isAir(tile, x, y - 1, z, kinds)) {
            score += DOWN_SCORE;
        }
        if (x > 0 && isAir(tile, x - 1, y, z, kinds)) {
            score += SIDE_SCORE;
        }
        if (x + 1 < TileVoxels.SIZE && isAir(tile, x + 1, y, z, kinds)) {
            score += SIDE_SCORE;
        }
        if (z > 0 && isAir(tile, x, y, z - 1, kinds)) {
            score += SIDE_SCORE;
        }
        if (z + 1 < TileVoxels.SIZE && isAir(tile, x, y, z + 1, kinds)) {
            score += SIDE_SCORE;
        }
        return score;
    }

    private static boolean isAir(TileVoxels tile, int x, int y, int z, byte[] kinds) {
        return kinds[TileVoxels.index(x, y, z)] == BlockKinds.AIR;
    }

    /** The most exposed state; ties go to the state most cells hold, then to the first. */
    static int winner(int[] states, int[] scores, int count) {
        int best = 0;
        for (int i = 1; i < count; i++) {
            if (scores[i] > scores[best] || (scores[i] == scores[best] && occurrences(states, count, states[i]) > occurrences(states, count, states[best]))) {
                best = i;
            }
        }
        return states[best];
    }

    private static int occurrences(int[] states, int count, int state) {
        int found = 0;
        for (int i = 0; i < count; i++) {
            if (states[i] == state) {
                found++;
            }
        }
        return found;
    }
}
