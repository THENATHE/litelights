package com.thenathe.litelights.render.feature.farterrain.land;

/**
 * A far terrain tile: {@link TileVoxels#SIZE} by {@link TileVoxels#SIZE} columns of voxels
 * {@code 1 << level} blocks wide, the world's full height. A level-0 tile covers 2 by 2 chunks; each
 * level up covers twice as far each way, so level 7 is 4,096 blocks across. Keys pack into a long.
 */
public final class TileKey {
    public static final int MAX_LEVEL = 7;
    /** Blocks across a level-0 tile. */
    public static final int BLOCKS = TileVoxels.SIZE;

    private static final int COORD_BITS = 28;
    private static final long COORD_MASK = (1L << COORD_BITS) - 1;

    private TileKey() {
    }

    public static long of(int level, int x, int z) {
        return (long) level << (2 * COORD_BITS) | (x & COORD_MASK) << COORD_BITS | (z & COORD_MASK);
    }

    public static int level(long key) {
        return (int) (key >>> (2 * COORD_BITS));
    }

    public static int x(long key) {
        return signExtend((key >>> COORD_BITS) & COORD_MASK);
    }

    public static int z(long key) {
        return signExtend(key & COORD_MASK);
    }

    private static int signExtend(long value) {
        return (int) (value << (64 - COORD_BITS) >> (64 - COORD_BITS));
    }

    /** Blocks across a tile of this level. */
    public static int span(int level) {
        return BLOCKS << level;
    }

    /** The world block x of the tile's west edge. */
    public static int originX(long key) {
        return x(key) * span(level(key));
    }

    public static int originZ(long key) {
        return z(key) * span(level(key));
    }

    /** The tile one level up that holds this one. */
    public static long parent(long key) {
        return of(level(key) + 1, x(key) >> 1, z(key) >> 1);
    }

    /** One of the four tiles a level down: quadrant 0 to 3, x in bit 0 and z in bit 1. */
    public static long child(long key, int quadrant) {
        return of(level(key) - 1, x(key) * 2 + (quadrant & 1), z(key) * 2 + (quadrant >> 1));
    }

    /** The level-0 tile holding this chunk. */
    public static long ofChunk(int chunkX, int chunkZ) {
        return of(0, chunkX >> 1, chunkZ >> 1);
    }

    /** The neighbour at the same level one step along x and z. */
    public static long neighbour(long key, int stepX, int stepZ) {
        return of(level(key), x(key) + stepX, z(key) + stepZ);
    }

    public static String describe(long key) {
        return "L" + level(key) + " " + x(key) + "," + z(key);
    }
}
