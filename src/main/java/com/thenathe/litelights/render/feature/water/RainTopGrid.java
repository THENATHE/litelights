package com.thenathe.litelights.render.feature.water;

/**
 * Where rain stops in each block column near the camera: the top of the highest block that blocks
 * motion or holds a fluid (vanilla's {@code MOTION_BLOCKING} heightmap, the same one vanilla's rain
 * is drawn down to). Kept beside the {@link FluidColumnGrid}, in the same wrapping square, and
 * uploaded to its own region of the fluid data texture, below the column map, so glass can tell
 * whether rain reaches the air in front of it without another texture unit (see
 * shaders/include/glass/exposure.glsl).
 *
 * <p>Texel: R and G the rain top in whole blocks above the level's lowest y, plus one (16 bits, 0 =
 * not known); B and A are 0. No Minecraft types here, so the packing is unit-tested on its own.
 */
public final class RainTopGrid {
    public static final int SIZE = FluidColumnGrid.SIZE;
    private static final int WRAP = SIZE - 1;
    private static final int MAX_PACKED = 0xFFFF;

    private final int[] packed = new int[SIZE * SIZE];

    /** The packed value of a column whose rain stops at world height {@code rainTopY}. */
    public static int pack(int rainTopY, int minY) {
        return Math.clamp(rainTopY - minY + 1, 1, MAX_PACKED);
    }

    /** Whether a packed value comes from a loaded column. */
    public static boolean known(int packedValue) {
        return packedValue != 0;
    }

    /** The world height rain stops at, from a known packed value. */
    public static int rainTopY(int packedValue, int minY) {
        return packedValue - 1 + minY;
    }

    public void set(int x, int z, int packedValue) {
        packed[(x & WRAP) + (z & WRAP) * SIZE] = packedValue;
    }

    public int get(int x, int z) {
        return packed[(x & WRAP) + (z & WRAP) * SIZE];
    }

    public void clearChunk(int chunkX, int chunkZ) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                set(chunkX * 16 + x, chunkZ * 16 + z, 0);
            }
        }
    }

    /** The shader texel for a column, as 0xAABBGGRR (bytes R, G, B, A in little-endian order). */
    public int texel(int x, int z) {
        return get(x, z) & MAX_PACKED;
    }
}
