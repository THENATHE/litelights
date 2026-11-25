package com.thenathe.litelights.render.feature.farterrain.land;

import java.util.Arrays;

/**
 * One tile's voxels, decoded: 32 by 32 columns, each {@code height} voxels from {@code minY} up.
 * At level L a voxel is {@code 1 << L} blocks on each side. States are ids in the dimension's
 * {@link BlockPalette} (0 is air); light is sky light in the high four bits and block light in the
 * low four, of the voxel itself (so an air voxel's light is the light on the faces it touches).
 * Plain data: built and read on worker threads, never shared while being written.
 */
public final class TileVoxels {
    public static final int SIZE = 32;
    public static final int COLUMNS = SIZE * SIZE;
    /** An edge column whose top is unknown: the land there was never seen. */
    public static final int UNKNOWN_TOP = Short.MIN_VALUE;
    /** An edge column that was seen but holds nothing solid. */
    public static final int EMPTY_TOP = -1;

    public final int level;
    /** World y of the bottom of voxel row 0. */
    public final int minY;
    /** Voxel rows. */
    public final int height;
    public final int[] states;
    public final byte[] light;
    /** Per column, the biome id (BlockPalette's biome table) of the land's top. */
    public final short[] biomes;
    /** Per column, whether the land there was ever seen. */
    public final long[] known = new long[COLUMNS / 64];
    /** Whether solid voxels' light bytes hold their insets: not in tiles written before format 2. */
    public boolean hasInsets = true;

    public TileVoxels(int level, int minY, int height) {
        this.level = level;
        this.minY = minY;
        this.height = height;
        int voxels = COLUMNS * height;
        this.states = new int[voxels];
        this.light = new byte[voxels];
        this.biomes = new short[COLUMNS];
    }

    /** Voxel rows for a world of this many blocks at this level. */
    public static int heightFor(int worldHeight, int level) {
        int size = 1 << level;
        return (worldHeight + size - 1) / size;
    }

    public static int index(int x, int y, int z) {
        return x + z * SIZE + y * COLUMNS;
    }

    public static int column(int x, int z) {
        return x + z * SIZE;
    }

    public int state(int x, int y, int z) {
        return states[index(x, y, z)];
    }

    public boolean known(int x, int z) {
        int column = column(x, z);
        return (known[column >> 6] & 1L << (column & 63)) != 0;
    }

    public void setKnown(int x, int z) {
        int column = column(x, z);
        known[column >> 6] |= 1L << (column & 63);
    }

    public boolean anyKnown() {
        for (long word : known) {
            if (word != 0) {
                return true;
            }
        }
        return false;
    }

    public int skyLight(int index) {
        return (light[index] >> 4) & 15;
    }

    public int blockLight(int index) {
        return light[index] & 15;
    }

    /**
     * How far below a solid voxel's top its land really ends, in blocks: at coarse levels a voxel is
     * solid if any block in it is, so a flat field at a level's voxel boundary minus one would
     * otherwise stand a block proud of the finer level beside it. Kept in the light byte of solid
     * voxels at levels above 0 (their own light means nothing: faces take the air's); 0 at level 0.
     */
    public static int inset(TileVoxels tile, int index) {
        if (tile.level == 0 || !tile.hasInsets) {
            return 0;
        }
        return Math.min(tile.light[index] & 0xFF, (1 << tile.level) - 1);
    }

    /** Every voxel's kind ({@link BlockKinds}), for code that asks about each voxel many times. */
    public byte[] kinds(BlockKinds kinds) {
        byte[] result = new byte[states.length];
        int lastState = -1;
        byte lastKind = 0;
        for (int i = 0; i < states.length; i++) {
            int state = states[i];
            if (state != lastState) {
                lastState = state;
                lastKind = (byte) kinds.kind(state);
            }
            result[i] = lastKind;
        }
        return result;
    }

    /** Clears one column to unseen air. */
    public void clearColumn(int x, int z) {
        for (int y = 0; y < height; y++) {
            int index = index(x, y, z);
            states[index] = 0;
            light[index] = 0;
        }
        int column = column(x, z);
        known[column >> 6] &= ~(1L << (column & 63));
        biomes[column] = 0;
    }

    /**
     * The highest voxel row holding something drawn (not air) in this column, {@link #EMPTY_TOP}
     * if none, {@link #UNKNOWN_TOP} if the column was never seen.
     */
    public int top(int x, int z, BlockKinds kinds) {
        if (!known(x, z)) {
            return UNKNOWN_TOP;
        }
        for (int y = height - 1; y >= 0; y--) {
            if (kinds.kind(states[index(x, y, z)]) != BlockKinds.AIR) {
                return y;
            }
        }
        return EMPTY_TOP;
    }

    /**
     * The tops of the columns along each edge (0 north z = 0, 1 south z = 31, 2 west x = 0, 3 east
     * x = 31), 32 each, for the neighbours' border faces.
     */
    public EdgeTops edgeTops(BlockKinds kinds) {
        int[][] tops = new int[4][SIZE];
        for (int i = 0; i < SIZE; i++) {
            tops[EdgeTops.NORTH][i] = top(i, 0, kinds);
            tops[EdgeTops.SOUTH][i] = top(i, SIZE - 1, kinds);
            tops[EdgeTops.WEST][i] = top(0, i, kinds);
            tops[EdgeTops.EAST][i] = top(SIZE - 1, i, kinds);
        }
        return new EdgeTops(tops);
    }

    public TileVoxels copy() {
        TileVoxels copy = new TileVoxels(level, minY, height);
        System.arraycopy(states, 0, copy.states, 0, states.length);
        System.arraycopy(light, 0, copy.light, 0, light.length);
        System.arraycopy(biomes, 0, copy.biomes, 0, biomes.length);
        System.arraycopy(known, 0, copy.known, 0, known.length);
        copy.hasInsets = hasInsets;
        return copy;
    }

    public boolean sameAs(TileVoxels other) {
        return level == other.level && minY == other.minY && height == other.height && Arrays.equals(states, other.states)
            && Arrays.equals(light, other.light) && Arrays.equals(biomes, other.biomes) && Arrays.equals(known, other.known);
    }
}
