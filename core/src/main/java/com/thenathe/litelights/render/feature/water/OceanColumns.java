package com.thenathe.litelights.render.feature.water;

/**
 * What the ocean waves need to know about each water column beyond the {@link FluidColumnGrid}:
 * whether it lies in an ocean or beach biome, and whether its surface is still (the waves step
 * aside for flowing water, Streams Reflowing's currents included). Kept in the same wrapping square
 * as the grid, and filled by the column map's scan only while Ocean waves is on.
 */
public final class OceanColumns {
    public static final int OCEAN_BIOME = 1;
    public static final int STILL = 2;
    /**
     * The scan read this water's surface itself. Water found under a cover (a dock, a pier) by
     * {@link CoveredWater} lacks it, and takes the swell of the water around it.
     */
    public static final int SCANNED = 4;
    private static final int SIZE = FluidColumnGrid.SIZE;
    private static final int WRAP = SIZE - 1;

    private final byte[] kinds = new byte[SIZE * SIZE];

    public void set(int x, int z, int kind) {
        kinds[(x & WRAP) + (z & WRAP) * SIZE] = (byte) kind;
    }

    public int get(int x, int z) {
        return kinds[(x & WRAP) + (z & WRAP) * SIZE];
    }

    public void clearChunk(int chunkX, int chunkZ) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                set(chunkX * 16 + x, chunkZ * 16 + z, 0);
            }
        }
    }

    /** Copies the window starting at these world columns, in window order (x + z * SIZE). */
    public void copyWindow(int minX, int minZ, byte[] out) {
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                out[x + z * SIZE] = (byte) get(minX + x, minZ + z);
            }
        }
    }
}
