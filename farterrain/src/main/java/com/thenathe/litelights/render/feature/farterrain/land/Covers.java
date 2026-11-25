package com.thenathe.litelights.render.feature.farterrain.land;

import java.util.function.IntPredicate;

/**
 * Thin covers (snow layers, carpets) take the place of the solid block they lie on, so a snowy
 * field reads white from afar instead of a block higher and bare.
 */
public final class Covers {
    private Covers() {
    }

    /**
     * Folds each cover in a column onto the solid block under it, once, and returns the row of the
     * column's top (the highest non-air voxel after folding, -1 if none).
     */
    public static int fold(TileVoxels tile, int x, int z, int rows, IntPredicate isCover, BlockKinds kinds) {
        int topRow = -1;
        for (int row = rows - 1; row >= 0; row--) {
            int index = TileVoxels.index(x, row, z);
            int state = tile.states[index];
            if (state == 0) {
                continue;
            }
            if (row > 0 && isCover.test(state)) {
                int below = TileVoxels.index(x, row - 1, z);
                if (kinds.kind(tile.states[below]) == BlockKinds.SOLID) {
                    tile.states[below] = state;
                    tile.states[index] = 0;
                    if (topRow < 0) {
                        topRow = row - 1;
                    }
                    // The folded cover now sits on the row below: it is done, and must not sink further.
                    row--;
                    continue;
                }
            }
            if (topRow < 0 && kinds.kind(state) != BlockKinds.AIR) {
                topRow = row;
            }
        }
        return topRow;
    }
}
