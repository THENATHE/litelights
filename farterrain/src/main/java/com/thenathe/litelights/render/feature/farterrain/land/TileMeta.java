package com.thenathe.litelights.render.feature.farterrain.land;

/**
 * What is known about a stored tile without decoding it: the rows its land spans (for culling),
 * its edge columns' tops (for the neighbours' border faces) and a revision that grows with every
 * write, so a mesh can tell it is out of date.
 *
 * @param lowestRow  the lowest voxel row holding anything but air, or 0 for a tile of air only
 * @param highestRow the highest, or -1 for a tile of air only
 */
public record TileMeta(int lowestRow, int highestRow, EdgeTops edges, long revision) {
    public boolean empty() {
        return highestRow < lowestRow;
    }

    public static TileMeta of(TileVoxels tile, BlockKinds blockKinds, long revision) {
        byte[] kinds = tile.kinds(blockKinds);
        int lowest = Integer.MAX_VALUE;
        int highest = -1;
        for (int y = 0; y < tile.height; y++) {
            for (int column = 0; column < TileVoxels.COLUMNS; column++) {
                if (kinds[column + y * TileVoxels.COLUMNS] != BlockKinds.AIR) {
                    lowest = Math.min(lowest, y);
                    highest = y;
                    break;
                }
            }
        }
        return new TileMeta(highest < 0 ? 0 : lowest, highest, tile.edgeTops(blockKinds), revision);
    }
}
