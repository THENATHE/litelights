package com.thenathe.litelights.render.feature.water;

/**
 * Which way and how fast the water at each column's surface flows, for foam that drifts downstream
 * (Foam motion: Drifting). The flow is the game's own ({@code FluidState.getFlow}), the vector
 * vanilla's and Sodium's fluid renderers turn the flow texture by, so it follows any mod that
 * answers it with a current of its own (Streams Reflowing's streams). Kept beside the
 * {@link RainTopGrid} in the same wrapping square, and uploaded into the blue and alpha bytes of the
 * rain tops' texels, which were unused, so it costs no texture of its own.
 *
 * <p>Texel bytes: B the flow along x, A along z, each a signed byte in 64ths of a block, so 0 is
 * still water (and every column the map doesn't know). Vanilla's flow is a unit vector; currents of
 * other mods may be longer, up to about two. No Minecraft types here, so the packing is unit-tested.
 */
public final class FluidFlowGrid {
    public static final int SIZE = FluidColumnGrid.SIZE;
    /** Steps of one flow component per unit of flow (include/water/common.glsl, lite_fluid_flow_at). */
    public static final int STEPS = 64;
    private static final int WRAP = SIZE - 1;

    private final short[] packed = new short[SIZE * SIZE];

    /** The two signed bytes of a flow, x in the low byte and z in the high byte. */
    public static int pack(double flowX, double flowZ) {
        return (step(flowX) & 0xFF) | (step(flowZ) & 0xFF) << 8;
    }

    /** The flow along x from a packed value, in blocks. */
    public static float flowX(int packedValue) {
        return (byte) packedValue / (float) STEPS;
    }

    /** The flow along z from a packed value, in blocks. */
    public static float flowZ(int packedValue) {
        return (byte) (packedValue >> 8) / (float) STEPS;
    }

    private static int step(double component) {
        if (!Double.isFinite(component)) {
            return 0;
        }
        return Math.clamp(Math.round(component * STEPS), Byte.MIN_VALUE + 1, Byte.MAX_VALUE);
    }

    public void set(int x, int z, int packedValue) {
        packed[(x & WRAP) + (z & WRAP) * SIZE] = (short) packedValue;
    }

    public int get(int x, int z) {
        return packed[(x & WRAP) + (z & WRAP) * SIZE] & 0xFFFF;
    }

    public void clearChunk(int chunkX, int chunkZ) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                set(chunkX * 16 + x, chunkZ * 16 + z, 0);
            }
        }
    }

    /** The column's bytes in place in a rain-top texel (0xAABBGGRR): blue and alpha. */
    public int texelBits(int x, int z) {
        return get(x, z) << 16;
    }
}
