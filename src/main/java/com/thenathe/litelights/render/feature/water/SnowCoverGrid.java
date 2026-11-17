package com.thenathe.litelights.render.feature.water;

/**
 * Where snow can gather on leaves and under trees near the camera (Snowy leaves and ground), kept
 * beside the {@link FluidColumnGrid} in the same wrapping square: for each column, whether the
 * highest block rain and snow stop on is leaves (so nothing but sky is over that canopy), whether
 * the biome there snows, what kind of tree it is, the canopy's height, and the ground under it if
 * that is bare ground a dusting can lie on.
 *
 * <p>{@link #texel} gives the texel of the snow cover region of the fluid data texture (from y
 * {@link FluidDataTexture#SNOW_COVER_Y}; shaders/include/weather/snow_cover.glsl reads it):
 * <ul>
 *   <li>R: {@link #CANOPY}, {@link #SNOWS}, {@link #CONIFER} and {@link #GROUND}, and in bits 4
 *       and 5 how far the nearest trunk is, in columns (0 to 2, 3 for none within two);</li>
 *   <li>G: the canopy's top leaf block's height modulo 256;</li>
 *   <li>B: the ground block's height modulo 256;</li>
 *   <li>A: how much of the land within two columns is under canopy, 0 to 250.</li>
 * </ul>
 * 0 for a column with no canopy, which takes no snow. No Minecraft types here.
 */
public final class SnowCoverGrid {
    public static final int SIZE = FluidColumnGrid.SIZE;
    /** The column's highest rain-stopping block is leaves. */
    public static final int CANOPY = 1;
    /** The biome at the canopy snows (cold enough there). */
    public static final int SNOWS = 2;
    /** Spruce and other needle-leaved trees: they hold more snow. */
    public static final int CONIFER = 4;
    /** The ground under the canopy is bare ground a dusting can lie on. */
    public static final int GROUND = 8;
    /** The column's top block but for leaves is a log: a trunk. Kept here, not in the texel. */
    public static final int TRUNK = 16;
    private static final int TEXEL_FLAGS = CANOPY | SNOWS | CONIFER | GROUND;
    private static final int TRUNK_SHIFT = 4;
    private static final int NO_TRUNK_NEAR = 3;
    private static final int RADIUS = 2;
    private static final int WRAP = SIZE - 1;

    private final byte[] flags = new byte[SIZE * SIZE];
    private final byte[] canopyHeights = new byte[SIZE * SIZE];
    private final byte[] groundHeights = new byte[SIZE * SIZE];

    /** Records a column: {@code columnFlags} from the constants above; the heights are world heights. */
    public void set(int x, int z, int columnFlags, int canopyY, int groundY) {
        int index = index(x, z);
        flags[index] = (byte) columnFlags;
        canopyHeights[index] = (byte) canopyY;
        groundHeights[index] = (byte) groundY;
    }

    public void clearChunk(int chunkX, int chunkZ) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                set(chunkX * 16 + x, chunkZ * 16 + z, 0, 0, 0);
            }
        }
    }

    /**
     * The shader texel for a column, as 0xAABBGGRR. {@code columns} says which columns are inside
     * the window, so the neighbours read for the trunk and the canopy around are never ones from the
     * window's far side.
     */
    public int texel(int x, int z, FluidColumnGrid columns) {
        int index = index(x, z);
        int columnFlags = flags[index] & 0xFF;
        if ((columnFlags & CANOPY) == 0 || !columns.inWindow(x, z)) {
            return 0;
        }
        int trunk = NO_TRUNK_NEAR;
        int covered = 0;
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                if (!columns.inWindow(x + dx, z + dz)) {
                    continue;
                }
                int neighbour = flags[index(x + dx, z + dz)];
                if ((neighbour & CANOPY) != 0) {
                    covered++;
                }
                if ((neighbour & TRUNK) != 0) {
                    trunk = Math.min(trunk, Math.max(Math.abs(dx), Math.abs(dz)));
                }
            }
        }
        int red = (columnFlags & TEXEL_FLAGS) | trunk << TRUNK_SHIFT;
        int green = canopyHeights[index] & 0xFF;
        int blue = (columnFlags & GROUND) != 0 ? groundHeights[index] & 0xFF : 0;
        int alpha = covered * 10;
        return red | green << 8 | blue << 16 | alpha << 24;
    }

    private static int index(int x, int z) {
        return (x & WRAP) + (z & WRAP) * SIZE;
    }
}
