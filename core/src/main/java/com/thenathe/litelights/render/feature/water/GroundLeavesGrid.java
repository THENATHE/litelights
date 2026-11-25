package com.thenathe.litelights.render.feature.water;

/**
 * Where fallen leaves may lie on the ground near the camera (Leaves under trees), kept beside the
 * {@link FluidColumnGrid} in the same wrapping square: for each column, the colour of the canopy
 * over it, and whether its top block is ground leaves may lie on (grass, dirt, moss and the like,
 * open above but for leaves, with no leaf litter or snow on it) and at what height.
 *
 * <p>{@link #texel} gives the texel of the ground leaves' region of the fluid data texture (from y
 * {@link FluidDataTexture#GROUND_LEAVES_Y}; shaders/include/vegetation/ground_leaves.glsl reads it):
 * <ul>
 *   <li>R, G, B: the colour of the canopy nearby divided by the ground's own tint, at a quarter
 *       scale (0 to 255 for 0 to 4). The terrain shader multiplies it by the ground's vertex colour,
 *       which is that tint times the ambient occlusion, so a leaf shows the canopy's colour shaded
 *       like the ground under it;</li>
 *   <li>A: canopy nearby in the low 4 bits (the floating leaves' density, from the same canopy) and
 *       the ground block's height modulo 16 in the high 4 bits. 0 for no leaves at all.</li>
 * </ul>
 * The colour is the canopy columns' within two columns, the nearest weighted most, so leaves under
 * an oak are oak's colour and those between an oak and a birch blend. No Minecraft types here.
 */
public final class GroundLeavesGrid {
    public static final int SIZE = FluidColumnGrid.SIZE;
    private static final int WRAP = SIZE - 1;
    private static final int RADIUS = 2;
    /** Marks a known canopy colour and a ground column; the low 24 bits hold the colour or the height. */
    private static final int PRESENT = 1 << 24;
    private static final int HEIGHT_MASK = 0xFFF;
    /** The ratio's scale in the texel: 64 steps per 1, so 255 is just under 4. */
    private static final int RATIO_STEPS = 64;
    /** A tint channel is taken as at least this, so a near-black tint can't blow the ratio up. */
    private static final int DARKEST_TINT = 16;

    /** Looks up the tint of the ground block at a column's ground height, as 0xRRGGBB. */
    public interface Tints {
        int tint(int x, int y, int z);
    }

    private final int[] canopy = new int[SIZE * SIZE];
    private final int[] ground = new int[SIZE * SIZE];
    /** The ground's tint, worked out the first time a texel with leaves needs it (0 = not yet). */
    private final int[] tints = new int[SIZE * SIZE];

    /** Records a column. {@code canopyColour} is 0xRRGGBB or -1 for none; {@code groundY} is the ground block's world height, or {@code Integer.MIN_VALUE} for no ground leaves may lie on. */
    public void set(int x, int z, int canopyColour, int groundY, int minY) {
        int index = index(x, z);
        canopy[index] = canopyColour < 0 ? 0 : PRESENT | (canopyColour & 0xFFFFFF);
        ground[index] = groundY == Integer.MIN_VALUE ? 0 : PRESENT | Math.clamp(groundY - minY, 0, HEIGHT_MASK);
        tints[index] = 0;
    }

    public void clearChunk(int chunkX, int chunkZ) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = index(chunkX * 16 + x, chunkZ * 16 + z);
                canopy[index] = 0;
                ground[index] = 0;
                tints[index] = 0;
            }
        }
    }

    /**
     * The shader texel for a column, as 0xAABBGGRR. {@code columns} says which columns are inside
     * the window and under canopy (its CANOPY bit, the same the floating leaves use).
     */
    public int texel(int x, int z, int minY, FluidColumnGrid columns, Tints groundTints) {
        int index = index(x, z);
        int groundColumn = ground[index];
        if (groundColumn == 0 || !columns.inWindow(x, z)) {
            return 0;
        }
        int density = columns.canopyDensity(x, z);
        if (density == 0) {
            return 0;
        }
        int red = 0;
        int green = 0;
        int blue = 0;
        int weights = 0;
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int colour = canopy[index(x + dx, z + dz)];
                if (colour == 0 || (columns.get(x + dx, z + dz) & FluidColumnGrid.CANOPY) == 0) {
                    continue;
                }
                int weight = RADIUS + 1 - Math.max(Math.abs(dx), Math.abs(dz));
                red += ((colour >> 16) & 0xFF) * weight;
                green += ((colour >> 8) & 0xFF) * weight;
                blue += (colour & 0xFF) * weight;
                weights += weight;
            }
        }
        if (weights == 0) {
            return 0;
        }
        int groundY = minY + (groundColumn & HEIGHT_MASK);
        if (tints[index] == 0) {
            tints[index] = PRESENT | (groundTints.tint(x, groundY, z) & 0xFFFFFF);
        }
        int tint = tints[index];
        int alpha = (groundY & 15) << 4 | density;
        return ratio(red, weights, (tint >> 16) & 0xFF) | ratio(green, weights, (tint >> 8) & 0xFF) << 8
            | ratio(blue, weights, tint & 0xFF) << 16 | alpha << 24;
    }

    /** One channel of the canopy's colour over the ground's tint, in the texel's quarter scale. */
    static int ratio(int weightedChannel, int weights, int tintChannel) {
        double colour = weightedChannel / (double) weights;
        double ratio = colour / Math.max(tintChannel, DARKEST_TINT);
        return Math.min(255, (int) Math.round(ratio * RATIO_STEPS));
    }

    private static int index(int x, int z) {
        return (x & WRAP) + (z & WRAP) * SIZE;
    }
}
