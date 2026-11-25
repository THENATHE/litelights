package com.thenathe.litelights.render.feature.water;

/**
 * What the water shaders know about each block column near the camera: the height and kind of the
 * topmost fluid surface, and whether tree canopy hangs over it. Stored as one int per column in a
 * square that wraps around (column x maps to x mod {@link #SIZE}), so moving the camera only
 * rewrites the columns that come into range.
 *
 * <p>{@link #texel} turns a column and its neighbours into the RGBA8 texel the shaders read
 * (shaders/include/water/common.glsl, {@code lite_fluid_column}):
 * <ul>
 *   <li>R, G: the surface height in 1/64 blocks above the level's lowest y (15 bits, 0 = no fluid),
 *       and the lava flag in G's top bit;</li>
 *   <li>B: which of the eight neighbours end the fluid at this level (the shore);</li>
 *   <li>A: canopy density nearby (low 4 bits) and how much water falls in from a higher neighbour
 *       (high 4 bits).</li>
 * </ul>
 * No Minecraft types here, so the packing is unit-tested on its own.
 */
public final class FluidColumnGrid {
    public static final int SIZE = 256;
    private static final int WRAP = SIZE - 1;

    public static final int HEIGHT_STEPS_PER_BLOCK = 64;
    private static final int HEIGHT_MASK = 0x7FFF;
    public static final int LAVA = 1 << 15;
    /** The top block is ice: no fluid surface, but it reflects. */
    public static final int ICE = 1 << 16;
    /** Leaves hang above this column's surface. */
    public static final int CANOPY = 1 << 17;
    /** The topmost fluid block falls (a waterfall seen from the side). */
    public static final int FALLING = 1 << 18;
    /**
     * The column was read from a loaded chunk. A column without it is unknown (its chunk isn't
     * loaded), which is never a shore: the water carries on there, drawn by a far-terrain mod or
     * not at all.
     */
    public static final int KNOWN = 1 << 19;
    /**
     * Bits 20 to 31: the top of the column's highest solid block (leaves aside), in whole blocks
     * above the level's lowest y. A dry neighbour is a shore only if this reaches the waterline.
     */
    private static final int SOLID_TOP_SHIFT = 20;
    private static final int SOLID_TOP_MASK = 0xFFF;

    // Neighbour bits of the shore mask, in the order the shader tests them.
    public static final int WEST = 1;
    public static final int EAST = 2;
    public static final int NORTH = 4;
    public static final int SOUTH = 8;
    public static final int NORTH_WEST = 16;
    public static final int NORTH_EAST = 32;
    public static final int SOUTH_WEST = 64;
    public static final int SOUTH_EAST = 128;
    private static final int[][] NEIGHBOURS = {
        {-1, 0, WEST}, {1, 0, EAST}, {0, -1, NORTH}, {0, 1, SOUTH},
        {-1, -1, NORTH_WEST}, {1, -1, NORTH_EAST}, {-1, 1, SOUTH_WEST}, {1, 1, SOUTH_EAST},
    };

    /** A neighbour this much lower is a drop, not a shore; this much higher pours water in. */
    private static final int SAME_LEVEL_STEPS = HEIGHT_STEPS_PER_BLOCK / 2;
    private static final int CANOPY_RADIUS = 2;

    private final int[] columns = new int[SIZE * SIZE];
    /** Edge foam only against blocks: see {@link #shoreMask}. */
    private boolean shoreOnlyAgainstBlocks;
    /** The window of world columns the grid holds now; anything outside it reads as unknown. */
    private boolean windowSet;
    private int windowMinX;
    private int windowMinZ;

    /**
     * Packs one column. {@code surfaceY} is the fluid surface's world height and {@code minY} the
     * level's lowest block; pass {@code Double.NaN} for a column without a fluid surface.
     * {@code solidTopY} is the world height of the top face of the column's highest solid block.
     */
    public static int column(double surfaceY, int minY, int solidTopY, boolean lava, boolean ice, boolean canopy, boolean falling) {
        int column = KNOWN | Math.clamp(solidTopY - minY, 0, SOLID_TOP_MASK) << SOLID_TOP_SHIFT;
        if (!Double.isNaN(surfaceY)) {
            int steps = (int) Math.round((surfaceY - minY) * HEIGHT_STEPS_PER_BLOCK);
            column |= Math.clamp(steps, 1, HEIGHT_MASK);
            if (lava) {
                column |= LAVA;
            }
            if (falling) {
                column |= FALLING;
            }
        }
        if (ice) {
            column |= ICE;
        }
        if (canopy) {
            column |= CANOPY;
        }
        return column;
    }

    /**
     * A column first read as dry, found to hold water under a cover (see {@link CoveredWater}): it
     * keeps what was known about it and gains the water's surface.
     */
    public static int covered(int dryColumn, double surfaceY, int minY) {
        int steps = (int) Math.round((surfaceY - minY) * HEIGHT_STEPS_PER_BLOCK);
        return (dryColumn & ~HEIGHT_MASK & ~ICE) | KNOWN | Math.clamp(steps, 1, HEIGHT_MASK);
    }

    public static int heightSteps(int column) {
        return column & HEIGHT_MASK;
    }

    public static boolean hasFluid(int column) {
        return heightSteps(column) != 0;
    }

    public static double surfaceY(int column, int minY) {
        return minY + heightSteps(column) / (double) HEIGHT_STEPS_PER_BLOCK;
    }

    /** The solid top's height above the level's lowest y, in the same 1/64 block steps as the surface. */
    static int solidTopSteps(int column) {
        return ((column >>> SOLID_TOP_SHIFT) & SOLID_TOP_MASK) * HEIGHT_STEPS_PER_BLOCK;
    }

    /**
     * Sets the window of world columns the grid holds: {@code SIZE} columns from these corners. The
     * grid wraps, so a column outside the window would otherwise read whatever wrapped into its
     * cell from the far side; it reads as unknown (0) instead.
     */
    public void setWindow(int minX, int minZ) {
        windowSet = true;
        windowMinX = minX;
        windowMinZ = minZ;
    }

    boolean inWindow(int x, int z) {
        return !windowSet || (x - windowMinX >= 0 && x - windowMinX < SIZE && z - windowMinZ >= 0 && z - windowMinZ < SIZE);
    }

    public int get(int x, int z) {
        if (!inWindow(x, z)) {
            return 0;
        }
        return columns[(x & WRAP) + (z & WRAP) * SIZE];
    }

    public void set(int x, int z, int column) {
        columns[(x & WRAP) + (z & WRAP) * SIZE] = column;
    }

    /** Copies the window's columns in window order (x + z * SIZE from its corner), unknown outside it. */
    public void copyWindow(int[] out) {
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                out[x + z * SIZE] = get(windowMinX + x, windowMinZ + z);
            }
        }
    }

    /** Clears a 16 x 16 chunk's columns, for a chunk that left the window or hasn't arrived yet. */
    public void clearChunk(int chunkX, int chunkZ) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                set(chunkX * 16 + x, chunkZ * 16 + z, 0);
            }
        }
    }

    /** The shader texel for a column, as 0xAABBGGRR (bytes R, G, B, A in little-endian order). */
    public int texel(int x, int z) {
        int column = get(x, z);
        int height = heightSteps(column);
        int red = height & 0xFF;
        int green = (height >> 8) & 0x7F;
        if ((column & LAVA) != 0) {
            green |= 0x80;
        }
        int shore = height == 0 ? 0 : shoreMask(x, z, column);
        int landing = height == 0 || (column & LAVA) != 0 ? 0 : landingStrength(x, z, height);
        int leaves = height == 0 ? 0 : canopyDensity(x, z);
        int alpha = (landing << 4) | leaves;
        return red | green << 8 | shore << 16 | alpha << 24;
    }

    /**
     * Whether a dry neighbour must stand beside the water, a block in the water's own top layer, to
     * be a shore (Foam only against blocks, on by default). Off, ground that only reaches half a
     * block under the surface counts too, which lines thin flowing water spread over flat ground with
     * foam where it simply runs out.
     */
    public void setShoreOnlyAgainstBlocks(boolean onlyAgainstBlocks) {
        shoreOnlyAgainstBlocks = onlyAgainstBlocks;
    }

    /**
     * Neighbours that end this column's fluid at its level: a solid block that reaches the
     * waterline, or the other kind of fluid. Only what is known counts: a neighbour in a chunk that
     * isn't loaded, outside the grid's window, or dry but lower than the waterline (the fluid runs
     * over the edge) is never a shore, so foam only ever draws against land that is really there.
     * Water beside water, at any height, is never a shore either, and nor is ice.
     *
     * <p>With only blocks counting, the waterline is at least the top of the block the surface is
     * in: the neighbour needs a block in that same layer. For a source block's surface that is the
     * same rule as before (whole blocks reaching half a block under it); it differs only for water
     * less than half a block deep, which no longer foams where it runs out on flat ground.
     */
    int shoreMask(int x, int z, int column) {
        int mask = 0;
        int height = heightSteps(column);
        int waterline = height - HEIGHT_STEPS_PER_BLOCK / 2;
        if (shoreOnlyAgainstBlocks) {
            int surfaceBlockTop = ((height - 1) / HEIGHT_STEPS_PER_BLOCK + 1) * HEIGHT_STEPS_PER_BLOCK;
            waterline = Math.max(waterline, surfaceBlockTop);
        }
        for (int[] neighbour : NEIGHBOURS) {
            int other = get(x + neighbour[0], z + neighbour[1]);
            if ((other & KNOWN) == 0) {
                continue;
            }
            boolean otherFluid = hasFluid(other) && (other & LAVA) != (column & LAVA);
            // Ice is no shore: the water carries on under it, so no foam lines an ice sheet or roof.
            boolean ice = (other & ICE) != 0;
            boolean solidAtWaterline = !hasFluid(other) && !ice && solidTopSteps(other) >= waterline;
            if (otherFluid || solidAtWaterline) {
                mask |= neighbour[2];
            }
        }
        return mask;
    }

    /** 0..15: how far water falls into this column from the highest neighbour (or itself), in blocks. */
    int landingStrength(int x, int z, int height) {
        int strongest = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int other = get(x + dx, z + dz);
                if (!hasFluid(other) || (other & LAVA) != 0) {
                    continue;
                }
                int drop = heightSteps(other) - height;
                if (drop > SAME_LEVEL_STEPS && ((other & FALLING) != 0 || drop >= 2 * HEIGHT_STEPS_PER_BLOCK)) {
                    strongest = Math.max(strongest, drop / HEIGHT_STEPS_PER_BLOCK);
                }
            }
        }
        return Math.min(15, strongest);
    }

    /** 0..15: columns with canopy above them within two columns. */
    int canopyDensity(int x, int z) {
        int count = 0;
        for (int dz = -CANOPY_RADIUS; dz <= CANOPY_RADIUS; dz++) {
            for (int dx = -CANOPY_RADIUS; dx <= CANOPY_RADIUS; dx++) {
                if ((get(x + dx, z + dz) & CANOPY) != 0) {
                    count++;
                }
            }
        }
        return Math.min(15, count);
    }
}
