package com.thenathe.litelights.render.feature.water;

/**
 * Finds water hidden under something solid: a dock, a bridge, a roof, an overhang or a branch
 * reaching over the water. The heightmaps the column map is built from stop at the cover, so such a
 * column first reads as dry land whose top is above the waterline, and shore foam would trace the
 * cover's outline across open water.
 *
 * <p>The search starts from water the map already knows and steps sideways, at that water's own
 * level, into columns whose block at that level holds the water's surface. So it only finds water
 * joined to known water at the same height: a pool in a cave under the land is never mistaken for
 * the lake beside it, and the land around it stays a shore.
 *
 * <p>One chunk at a time: columns of the chunk being scanned may change; water in the chunks around
 * it seeds the search but is never changed. Covered water that carries on into a neighbouring
 * chunk is reported, so that chunk can be scanned again with this one's water as its seed.
 *
 * <p>No Minecraft types here: blocks are read through {@link Blocks}, so it is unit-tested.
 */
final class CoveredWater {
    interface Blocks {
        /**
         * The surface height (world y) of water whose top is in block (x, y, z): water in that
         * block and none in the block above. NaN otherwise, and for blocks that aren't loaded.
         */
        double waterSurface(int x, int y, int z);
    }

    interface CrossBorder {
        /** Covered water at this level carries on into another chunk's column. */
        void found(int x, int z);
    }

    private static final int[][] SIDES = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    /** 16 x 16 columns of the chunk plus the ring of 68 columns around it, each queued at most once. */
    private final int[] queueX = new int[18 * 18];
    private final int[] queueZ = new int[18 * 18];

    /**
     * Marks the covered water of the 16 x 16 chunk whose first column is (baseX, baseZ) in the grid.
     * Returns how many columns it found.
     */
    int flood(FluidColumnGrid grid, int minY, int baseX, int baseZ, Blocks blocks, CrossBorder crossBorder) {
        int head = 0;
        int tail = 0;
        for (int z = baseZ - 1; z <= baseZ + 16; z++) {
            for (int x = baseX - 1; x <= baseX + 16; x++) {
                if (isWater(grid.get(x, z))) {
                    queueX[tail] = x;
                    queueZ[tail] = z;
                    tail++;
                }
            }
        }
        int found = 0;
        while (head < tail) {
            int x = queueX[head];
            int z = queueZ[head];
            head++;
            int column = grid.get(x, z);
            double surface = FluidColumnGrid.surfaceY(column, minY);
            int level = (int) Math.floor(surface - 1.0e-4);
            boolean fromInside = inChunk(x, z, baseX, baseZ);
            for (int[] side : SIDES) {
                int nextX = x + side[0];
                int nextZ = z + side[1];
                int next = grid.get(nextX, nextZ);
                if ((next & FluidColumnGrid.KNOWN) == 0 || FluidColumnGrid.hasFluid(next) || (next & FluidColumnGrid.ICE) != 0) {
                    continue;
                }
                boolean inside = inChunk(nextX, nextZ, baseX, baseZ);
                if (!inside && !fromInside) {
                    continue;
                }
                double nextSurface = blocks.waterSurface(nextX, level, nextZ);
                if (Double.isNaN(nextSurface)) {
                    continue;
                }
                if (!inside) {
                    crossBorder.found(nextX, nextZ);
                    continue;
                }
                grid.set(nextX, nextZ, FluidColumnGrid.covered(next, nextSurface, minY));
                queueX[tail] = nextX;
                queueZ[tail] = nextZ;
                tail++;
                found++;
            }
        }
        return found;
    }

    private static boolean isWater(int column) {
        return FluidColumnGrid.hasFluid(column) && (column & FluidColumnGrid.LAVA) == 0;
    }

    private static boolean inChunk(int x, int z, int baseX, int baseZ) {
        return x >= baseX && x < baseX + 16 && z >= baseZ && z < baseZ + 16;
    }
}
