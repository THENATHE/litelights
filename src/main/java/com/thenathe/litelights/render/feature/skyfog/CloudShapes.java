package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.render.LiteMath;
import java.util.Arrays;

/**
 * LiteLights' cloud maps: which cells of a repeating grid hold cloud. Generated, never read from
 * a texture, so they are the same for everyone and need no art.
 *
 * <p>The shapes come from a few octaves of value noise whose lattices repeat exactly across the
 * map, pushed around by a second, slower noise (domain warping), which turns round blobs into the
 * lumpy, uneven clusters of real cumulus. A threshold then picks the requested share of cells, and
 * lone cells and pinholes are cleaned up so no one-cell specks or holes are left.
 */
public final class CloudShapes {
    /** Cell flags, one byte per cell. The direction flags say the neighbour that way is empty. */
    public static final byte FILLED = 1;
    public static final byte NORTH_OPEN = 2;
    public static final byte EAST_OPEN = 4;
    public static final byte SOUTH_OPEN = 8;
    public static final byte WEST_OPEN = 16;

    private CloudShapes() {
    }

    /**
     * Low, puffy cumulus: clusters a few cells to a few dozen cells across with clear sky between.
     */
    public static byte[] cumulus(int size) {
        boolean[] cloud = threshold(field(size, 0x51C0FFEEL, 16, 16, 0.55f, 6.0f), 0.30f);
        return pack(clean(cloud, size), size);
    }

    /**
     * A higher, thinner layer: smaller patches stretched along the wind, like altocumulus bands.
     */
    public static byte[] altocumulus(int size) {
        boolean[] cloud = threshold(field(size, 0x0A17C0DEL, 32, 8, 0.40f, 4.0f), 0.22f);
        return pack(clean(cloud, size), size);
    }

    /**
     * A repeating noise field of {@code size} by {@code size}.
     *
     * @param spanX lattice spacing of the largest octave along X, in cells (must divide size)
     * @param spanZ the same along Z (different spans stretch the shapes)
     * @param baseWeight weight of the largest octave; the rest goes to two finer ones
     * @param warp how far, in cells, the slow noise pushes the sample point
     */
    static float[] field(int size, long seed, int spanX, int spanZ, float baseWeight, float warp) {
        float[] values = new float[size * size];
        float middleWeight = (1.0f - baseWeight) * 0.65f;
        float fineWeight = 1.0f - baseWeight - middleWeight;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                float warpX = (periodicNoise(x, z, size, 32, 32, seed ^ 0x1234L) - 0.5f) * 2.0f * warp;
                float warpZ = (periodicNoise(x, z, size, 32, 32, seed ^ 0x9876L) - 0.5f) * 2.0f * warp;
                float sampleX = x + warpX;
                float sampleZ = z + warpZ;
                float value = baseWeight * periodicNoise(sampleX, sampleZ, size, spanX, spanZ, seed)
                    + middleWeight * periodicNoise(sampleX, sampleZ, size, Math.max(spanX / 2, 1), Math.max(spanZ / 2, 1), seed + 1)
                    + fineWeight * periodicNoise(sampleX, sampleZ, size, Math.max(spanX / 4, 1), Math.max(spanZ / 4, 1), seed + 2);
                values[x + z * size] = value;
            }
        }
        return values;
    }

    /** Smooth value noise on a lattice that repeats every {@code size} cells. */
    static float periodicNoise(float x, float z, int size, int spanX, int spanZ, long seed) {
        int periodX = size / spanX;
        int periodZ = size / spanZ;
        float gridX = x / spanX;
        float gridZ = z / spanZ;
        int cellX = (int) Math.floor(gridX);
        int cellZ = (int) Math.floor(gridZ);
        float fx = smooth(gridX - cellX);
        float fz = smooth(gridZ - cellZ);
        float a = lattice(cellX, cellZ, periodX, periodZ, seed);
        float b = lattice(cellX + 1, cellZ, periodX, periodZ, seed);
        float c = lattice(cellX, cellZ + 1, periodX, periodZ, seed);
        float d = lattice(cellX + 1, cellZ + 1, periodX, periodZ, seed);
        return LiteMath.lerp(LiteMath.lerp(a, b, fx), LiteMath.lerp(c, d, fx), fz);
    }

    private static float smooth(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lattice(int x, int z, int periodX, int periodZ, long seed) {
        long wrappedX = Math.floorMod(x, periodX);
        long wrappedZ = Math.floorMod(z, periodZ);
        long hash = seed * 0x9E3779B97F4A7C15L + wrappedX * 0xC2B2AE3D27D4EB4FL + wrappedZ * 0x165667B19E3779F9L;
        hash ^= hash >>> 31;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 29;
        return (hash >>> 40) / (float) (1L << 24);
    }

    /** Marks the highest {@code coverage} share of the field as cloud. */
    static boolean[] threshold(float[] values, float coverage) {
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        float cut = sorted[Math.min(sorted.length - 1, (int) ((1.0f - coverage) * sorted.length))];
        boolean[] cloud = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            cloud[i] = values[i] >= cut;
        }
        return cloud;
    }

    /**
     * Removes cells with fewer than two cloudy neighbours and fills holes with three or more,
     * repeating until nothing changes (removing one speck can leave another).
     */
    static boolean[] clean(boolean[] cloud, int size) {
        boolean[] current = cloud;
        for (int pass = 0; pass < 8; pass++) {
            boolean[] next = cleanOnce(current, size);
            if (Arrays.equals(next, current)) {
                return next;
            }
            current = next;
        }
        return current;
    }

    private static boolean[] cleanOnce(boolean[] cloud, int size) {
        boolean[] cleaned = cloud.clone();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int neighbours = count(cloud, size, x + 1, z) + count(cloud, size, x - 1, z)
                    + count(cloud, size, x, z + 1) + count(cloud, size, x, z - 1);
                int index = x + z * size;
                if (cloud[index] && neighbours < 2) {
                    cleaned[index] = false;
                } else if (!cloud[index] && neighbours >= 3) {
                    cleaned[index] = true;
                }
            }
        }
        return cleaned;
    }

    private static int count(boolean[] cloud, int size, int x, int z) {
        return cloud[Math.floorMod(x, size) + Math.floorMod(z, size) * size] ? 1 : 0;
    }

    /** One byte per cell: FILLED, and which neighbours are empty (their faces are visible). */
    static byte[] pack(boolean[] cloud, int size) {
        byte[] cells = new byte[cloud.length];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int index = x + z * size;
                if (!cloud[index]) {
                    continue;
                }
                int flags = FILLED;
                flags |= count(cloud, size, x, z - 1) == 0 ? NORTH_OPEN : 0;
                flags |= count(cloud, size, x + 1, z) == 0 ? EAST_OPEN : 0;
                flags |= count(cloud, size, x, z + 1) == 0 ? SOUTH_OPEN : 0;
                flags |= count(cloud, size, x - 1, z) == 0 ? WEST_OPEN : 0;
                cells[index] = (byte) flags;
            }
        }
        return cells;
    }
}
