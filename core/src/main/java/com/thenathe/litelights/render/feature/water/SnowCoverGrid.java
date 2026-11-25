package com.thenathe.litelights.render.feature.water;

import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * Where snow can gather on leaves and under trees near the camera, and which plants stand in snow
 * (Snowy leaves and ground), kept
 * beside the {@link FluidColumnGrid} in the same wrapping square: for each column, whether the
 * highest block rain and snow stop on is leaves (so nothing but sky is over that canopy), whether
 * the biome there snows, what kind of tree it is, the canopy's height, and the ground under it if
 * that is bare ground a dusting can lie on.
 *
 * <p>{@link #texel} gives the texel of the snow cover region of the fluid data texture (from y
 * {@link FluidDataTexture#SNOW_COVER_Y}; shaders/include/weather/snow_cover.glsl reads it):
 * <ul>
 *   <li>R: {@link #CANOPY}, {@link #SNOWS}, {@link #CONIFER}, {@link #GROUND} and
 *       {@link #PLANT_IN_SNOW}, and in bits 4 and 5 how far the nearest trunk is, in columns (0 to 2,
 *       3 for none within two);</li>
 *   <li>G: the canopy's top leaf block's height modulo 256;</li>
 *   <li>B: the ground block's height modulo 256 (the plant stands on it);</li>
 *   <li>A: how much of the land within two columns is under canopy, 0 to 250; or, while the
 *       visual snow fans snow in under trees or overhangs, how much of it reaches the column
 *       ({@link SnowFanField}), 250 for open snow it fans from.</li>
 * </ul>
 * Under an overhang (visual snow's Snow under overhangs) R has {@link #OVERHANG} and
 * {@link #GROUND}, and B the ground's height under the overhang. 0 for a column with none of these.
 * No Minecraft types here.
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
    /**
     * The block on the column's ground is not a full block (grass, a fern, a flower) and has snow
     * layers beside it: the shader turns plants there white, as snowy grass. Bit 6 of the texel.
     */
    public static final int PLANT_IN_SNOW = 64;
    /** Bare ground under a roof or another overhang, where the snow beside fans in. Bit 7 of the texel. */
    public static final int OVERHANG = 128;
    private static final int TEXEL_FLAGS = CANOPY | SNOWS | CONIFER | GROUND | PLANT_IN_SNOW | OVERHANG;
    private static final int TRUNK_SHIFT = 4;
    private static final int NO_TRUNK_NEAR = 3;
    private static final int RADIUS = 2;
    private static final int WRAP = SIZE - 1;

    private final byte[] flags = new byte[SIZE * SIZE];
    private final byte[] canopyHeights = new byte[SIZE * SIZE];
    private final byte[] groundHeights = new byte[SIZE * SIZE];
    private final byte[] fanKinds = new byte[SIZE * SIZE];
    private final int[] fanHeights = new int[SIZE * SIZE];
    private final byte[] fan = new byte[SIZE * SIZE];
    private final byte[] localKinds = new byte[SIZE * SIZE];
    private final int[] localHeights = new int[SIZE * SIZE];
    private final byte[] localFan = new byte[SIZE * SIZE];
    private final SnowFanField fanField = new SnowFanField(SIZE);
    private boolean fanning;

    /** Records a column: {@code columnFlags} from the constants above; the heights are world heights. */
    public void set(int x, int z, int columnFlags, int canopyY, int groundY) {
        int index = index(x, z);
        flags[index] = (byte) columnFlags;
        canopyHeights[index] = (byte) canopyY;
        groundHeights[index] = (byte) groundY;
    }

    /** What the fanning sees in a column: a {@link SnowFanField} kind, at the height of its ground. */
    public void setFan(int x, int z, byte kind, int groundY) {
        int index = index(x, z);
        fanKinds[index] = kind;
        fanHeights[index] = groundY;
    }

    public void clearChunk(int chunkX, int chunkZ) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                set(chunkX * 16 + x, chunkZ * 16 + z, 0, 0, 0);
                setFan(chunkX * 16 + x, chunkZ * 16 + z, SnowFanField.BLOCKED, 0);
            }
        }
    }

    /** Whether the texels carry the fanning in A (and overhangs and open snow), rather than the canopy around. */
    public void setFanning(boolean on) {
        fanning = on;
        if (!on) {
            Arrays.fill(fan, (byte) 0);
        }
    }

    public boolean fanning() {
        return fanning;
    }

    /**
     * Works the fanning out again over the window whose corner column is ({@code originX},
     * {@code originZ}); {@code changedChunk} hears every chunk of the window (as its corner column)
     * whose fan changed, so only those are written again.
     */
    public void computeFan(int originX, int originZ, BiConsumer<Integer, Integer> changedChunk) {
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int index = index(originX + x, originZ + z);
                localKinds[z * SIZE + x] = fanKinds[index];
                localHeights[z * SIZE + x] = fanHeights[index];
            }
        }
        fanField.compute(localKinds, localHeights, localFan);
        for (int chunkZ = 0; chunkZ < SIZE / 16; chunkZ++) {
            for (int chunkX = 0; chunkX < SIZE / 16; chunkX++) {
                boolean changed = false;
                for (int z = chunkZ * 16; z < chunkZ * 16 + 16; z++) {
                    for (int x = chunkX * 16; x < chunkX * 16 + 16; x++) {
                        int index = index(originX + x, originZ + z);
                        byte value = localFan[z * SIZE + x];
                        if (fan[index] != value) {
                            fan[index] = value;
                            changed = true;
                        }
                    }
                }
                if (changed) {
                    changedChunk.accept(originX + chunkX * 16, originZ + chunkZ * 16);
                }
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
        int fanned = fanning ? fan[index] & 0xFF : 0;
        if (((columnFlags & (CANOPY | PLANT_IN_SNOW | OVERHANG)) == 0 && fanned == 0) || !columns.inWindow(x, z)) {
            return 0;
        }
        if ((columnFlags & CANOPY) == 0) {
            int ground = fanKinds[index] == SnowFanField.SOURCE ? fanHeights[index] : groundHeights[index];
            return (columnFlags & (PLANT_IN_SNOW | OVERHANG | GROUND | SNOWS)) | (ground & 0xFF) << 16 | fanned << 24;
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
        int blue = (columnFlags & (GROUND | PLANT_IN_SNOW)) != 0 ? groundHeights[index] & 0xFF : 0;
        int alpha = fanning && fanKinds[index] != SnowFanField.BLOCKED ? fanned : covered * 10;
        return red | green << 8 | blue << 16 | alpha << 24;
    }

    private static int index(int x, int z) {
        return (x & WRAP) + (z & WRAP) * SIZE;
    }
}
