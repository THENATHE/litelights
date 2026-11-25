package com.thenathe.litelights.render.feature.farterrain.land;

import java.util.Arrays;

/**
 * The tops of a tile's 32 columns along each of its four edges, in the tile's own voxel rows
 * ({@link TileVoxels#UNKNOWN_TOP} for land never seen). A neighbour meshes its border faces against
 * these without decoding the tile.
 */
public record EdgeTops(int[][] tops) {
    public static final int NORTH = 0;
    public static final int SOUTH = 1;
    public static final int WEST = 2;
    public static final int EAST = 3;

    public static final EdgeTops UNKNOWN = unknown();

    private static EdgeTops unknown() {
        int[][] tops = new int[4][TileVoxels.SIZE];
        for (int[] edge : tops) {
            Arrays.fill(edge, TileVoxels.UNKNOWN_TOP);
        }
        return new EdgeTops(tops);
    }

    public int top(int edge, int index) {
        return tops[edge][index];
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EdgeTops edges && Arrays.deepEquals(tops, edges.tops);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(tops);
    }
}
