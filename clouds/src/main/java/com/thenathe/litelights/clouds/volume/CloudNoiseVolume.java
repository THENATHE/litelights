package com.thenathe.litelights.clouds.volume;

/**
 * The 3D noise the volumetric clouds are worn into shape with: {@link #PERIOD} voxels a side, tiling
 * in every direction, laid out as a 2D atlas of its slices (the render API has no 3D textures), an
 * RGBA8 texture {@link #ATLAS_SIZE} square. Slice {@code z} is tile ({@code z % 8}, {@code z / 8}),
 * each {@link #TILE} texels square: the slice with a one-texel border of the opposite edge all
 * round, so the GPU's filtering wraps within a tile and never reads the next. The shader blends two
 * slices for the third axis (cloud_volume.glsl, {@code lite_cloud_noise}).
 *
 * <ul>
 *   <li><b>red</b>: the clouds' billows: low-frequency gradient noise with cellular noise (inverted
 *   distance to the nearest of a scatter of points) at three scales, so its peaks are round heads;</li>
 *   <li><b>green</b>: finer cellular noise at three scales, for the small heads on a cloud's edge;</li>
 *   <li><b>blue</b> and <b>alpha</b>: two smooth gradient noises, a vector the shader pushes the map
 *   lookup about by, so a cloud's outline leans, bulges and overhangs with height.</li>
 * </ul>
 *
 * <p>Every channel is stretched to fill its range, so the shader's thresholds mean the same in
 * each. A pure function of nothing: the same bytes on every machine (integer hashes, plain
 * arithmetic, no {@link Math#sin} or {@link Math#pow}).
 */
public final class CloudNoiseVolume {
    public static final int PERIOD = 64;
    public static final int TILE = PERIOD + 2;
    public static final int TILES_ACROSS = 8;
    public static final int ATLAS_SIZE = TILE * TILES_ACROSS;

    private static final long SEED = 0xC10D5EEDL;

    private CloudNoiseVolume() {
    }

    /** The atlas's bytes, RGBA8, rows of {@link #ATLAS_SIZE} texels. Takes a few tenths of a second: call it off the render thread. */
    public static byte[] build() {
        return atlas(billowChannels());
    }

    /**
     * The heaped and storm clouds' lumps, in the same layout: smooth-cornered cellular noise at 4,
     * 8 and 16 cells across the period in red, green and blue, so the shader can stack big lumps
     * carrying smaller ones like a cauliflower's head, with rounded clefts between them (plain
     * cellular noise creases along flat cell walls, which seen edge-on draw straight lines); alpha
     * is smooth gradient noise, for the broad rise and fall of a cloud's base.
     */
    public static byte[] buildLumps() {
        int voxels = PERIOD * PERIOD * PERIOD;
        float[][] channels = new float[4][voxels];
        for (int z = 0; z < PERIOD; z++) {
            for (int y = 0; y < PERIOD; y++) {
                for (int x = 0; x < PERIOD; x++) {
                    double px = (x + 0.5) / PERIOD;
                    double py = (y + 0.5) / PERIOD;
                    double pz = (z + 0.5) / PERIOD;
                    int voxel = (z * PERIOD + y) * PERIOD + x;
                    channels[0][voxel] = (float) roundedCells(px, py, pz, 4, SEED + 21);
                    channels[1][voxel] = (float) roundedCells(px, py, pz, 8, SEED + 22);
                    channels[2][voxel] = (float) roundedCells(px, py, pz, 16, SEED + 23);
                    channels[3][voxel] = (float) (0.7 * gradient(px, py, pz, 4, SEED + 24) + 0.3 * gradient(px, py, pz, 8, SEED + 25));
                }
            }
        }
        return atlas(channels);
    }

    private static float[][] billowChannels() {
        int voxels = PERIOD * PERIOD * PERIOD;
        float[][] channels = new float[4][voxels];
        for (int z = 0; z < PERIOD; z++) {
            for (int y = 0; y < PERIOD; y++) {
                for (int x = 0; x < PERIOD; x++) {
                    double px = (x + 0.5) / PERIOD;
                    double py = (y + 0.5) / PERIOD;
                    double pz = (z + 0.5) / PERIOD;
                    int voxel = (z * PERIOD + y) * PERIOD + x;
                    double gradient = 0.5 + 0.5 * (0.6 * gradient(px, py, pz, 4, SEED) + 0.3 * gradient(px, py, pz, 8, SEED + 1)
                        + 0.1 * gradient(px, py, pz, 16, SEED + 2));
                    // Mostly the coarsest cells, so the billows are big round lobes with a little finer texture.
                    double cells = 0.7 * cellular(px, py, pz, 4, SEED + 3) + 0.2 * cellular(px, py, pz, 8, SEED + 4)
                        + 0.1 * cellular(px, py, pz, 16, SEED + 5);
                    channels[0][voxel] = (float) (0.3 * gradient + 0.7 * cells);
                    channels[1][voxel] = (float) (0.625 * cellular(px, py, pz, 8, SEED + 6) + 0.25 * cellular(px, py, pz, 16, SEED + 7)
                        + 0.125 * cellular(px, py, pz, 32, SEED + 8));
                    channels[2][voxel] = (float) gradient(px, py, pz, 4, SEED + 9);
                    channels[3][voxel] = (float) gradient(px, py, pz, 4, SEED + 10);
                }
            }
        }
        return channels;
    }

    /** The channels stretched to their range and laid out as the atlas. */
    private static byte[] atlas(float[][] channels) {
        for (float[] channel : channels) {
            stretch(channel);
        }
        byte[] atlas = new byte[ATLAS_SIZE * ATLAS_SIZE * 4];
        for (int z = 0; z < PERIOD; z++) {
            int tileX = (z % TILES_ACROSS) * TILE;
            int tileY = (z / TILES_ACROSS) * TILE;
            for (int j = 0; j < TILE; j++) {
                int y = Math.floorMod(j - 1, PERIOD);
                for (int i = 0; i < TILE; i++) {
                    int x = Math.floorMod(i - 1, PERIOD);
                    int voxel = (z * PERIOD + y) * PERIOD + x;
                    int texel = ((tileY + j) * ATLAS_SIZE + tileX + i) * 4;
                    for (int c = 0; c < 4; c++) {
                        atlas[texel + c] = (byte) Math.clamp(Math.round(channels[c][voxel] * 255.0f), 0, 255);
                    }
                }
            }
        }
        return atlas;
    }

    /** Stretches a channel so its 1st percentile is 0 and its 99th 1. */
    private static void stretch(float[] values) {
        int bins = 1_024;
        float lowest = Float.POSITIVE_INFINITY;
        float highest = Float.NEGATIVE_INFINITY;
        for (float value : values) {
            lowest = Math.min(lowest, value);
            highest = Math.max(highest, value);
        }
        int[] counts = new int[bins];
        float width = Math.max(highest - lowest, 1.0e-6f);
        for (float value : values) {
            counts[Math.min(bins - 1, (int) ((value - lowest) / width * bins))]++;
        }
        float low = percentile(counts, values.length, 0.01, lowest, width);
        float high = percentile(counts, values.length, 0.99, lowest, width);
        float range = Math.max(high - low, 1.0e-6f);
        for (int i = 0; i < values.length; i++) {
            values[i] = Math.clamp((values[i] - low) / range, 0.0f, 1.0f);
        }
    }

    private static float percentile(int[] counts, int total, double share, float lowest, float width) {
        long below = 0;
        for (int bin = 0; bin < counts.length; bin++) {
            below += counts[bin];
            if (below >= share * total) {
                return lowest + (bin + 0.5f) / counts.length * width;
            }
        }
        return lowest + width;
    }

    /**
     * Gradient noise of {@code frequency} cells across the period, tiling, about -1..1, at a point
     * given in periods.
     */
    static double gradient(double x, double y, double z, int frequency, long seed) {
        double fx = x * frequency;
        double fy = y * frequency;
        double fz = z * frequency;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int z0 = (int) Math.floor(fz);
        double tx = fx - x0;
        double ty = fy - y0;
        double tz = fz - z0;
        double sum = 0.0;
        for (int corner = 0; corner < 8; corner++) {
            int cx = corner & 1;
            int cy = corner >> 1 & 1;
            int cz = corner >> 2 & 1;
            long hash = hash(seed, Math.floorMod(x0 + cx, frequency), Math.floorMod(y0 + cy, frequency), Math.floorMod(z0 + cz, frequency));
            double dx = tx - cx;
            double dy = ty - cy;
            double dz = tz - cz;
            double dot = switch ((int) Long.remainderUnsigned(hash, 12)) {
                case 0 -> dx + dy;
                case 1 -> -dx + dy;
                case 2 -> dx - dy;
                case 3 -> -dx - dy;
                case 4 -> dx + dz;
                case 5 -> -dx + dz;
                case 6 -> dx - dz;
                case 7 -> -dx - dz;
                case 8 -> dy + dz;
                case 9 -> -dy + dz;
                case 10 -> dy - dz;
                default -> -dy - dz;
            };
            double weight = (cx == 1 ? fade(tx) : 1.0 - fade(tx)) * (cy == 1 ? fade(ty) : 1.0 - fade(ty)) * (cz == 1 ? fade(tz) : 1.0 - fade(tz));
            sum += weight * dot;
        }
        return Math.clamp(sum, -1.0, 1.0);
    }

    /**
     * Cellular noise of {@code frequency} cells across the period, tiling: 1 at a cell's point,
     * falling to 0 a cell away, so its peaks are round.
     */
    static double cellular(double x, double y, double z, int frequency, long seed) {
        double fx = x * frequency;
        double fy = y * frequency;
        double fz = z * frequency;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int z0 = (int) Math.floor(fz);
        double nearest = Double.POSITIVE_INFINITY;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int cx = x0 + dx;
                    int cy = y0 + dy;
                    int cz = z0 + dz;
                    long hash = hash(seed, Math.floorMod(cx, frequency), Math.floorMod(cy, frequency), Math.floorMod(cz, frequency));
                    double px = cx + unit(hash) - fx;
                    double py = cy + unit(next(hash)) - fy;
                    double pz = cz + unit(next(next(hash))) - fz;
                    nearest = Math.min(nearest, px * px + py * py + pz * pz);
                }
            }
        }
        return 1.0 - Math.min(Math.sqrt(nearest), 1.0);
    }

    /**
     * Cellular noise with rounded creases: the distance to the nearest few points blended by a
     * smooth minimum, so the value peaks in a rounded lump over each point and dips into a soft
     * cleft between two, never along a sharp wall. 1 at a lump's middle, falling toward 0.
     */
    static double roundedCells(double x, double y, double z, int frequency, long seed) {
        double fx = x * frequency;
        double fy = y * frequency;
        double fz = z * frequency;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int z0 = (int) Math.floor(fz);
        double sum = 0.0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int cx = x0 + dx;
                    int cy = y0 + dy;
                    int cz = z0 + dz;
                    long hash = hash(seed, Math.floorMod(cx, frequency), Math.floorMod(cy, frequency), Math.floorMod(cz, frequency));
                    double px = cx + unit(hash) - fx;
                    double py = cy + unit(next(hash)) - fy;
                    double pz = cz + unit(next(next(hash))) - fz;
                    double upward = py / LUMP_HEIGHT;
                    double distance = Math.sqrt(px * px + upward * upward + pz * pz);
                    sum += Math.exp(-distance / ROUNDING);
                }
            }
        }
        double smoothNearest = -ROUNDING * Math.log(sum);
        double lump = 1.0 - Math.clamp(smoothNearest, 0.0, 1.0);
        // Rounded on top too: a dome over each point rather than a cone.
        return lump * lump * (3.0 - 2.0 * lump);
    }

    /** How soft the clefts between lumps are, in cells: the smooth minimum's width. */
    private static final double ROUNDING = 0.12;
    /** How much taller than wide a lump is, so heaped clouds stack upward in turrets. */
    private static final double LUMP_HEIGHT = 1.25;

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static long hash(long seed, int x, int y, int z) {
        long h = seed;
        h = next(h ^ (x * 0x9E3779B97F4A7C15L));
        h = next(h ^ (y * 0xC2B2AE3D27D4EB4FL));
        h = next(h ^ (z * 0x165667B19E3779F9L));
        return h;
    }

    /** SplitMix64's step. */
    private static long next(long state) {
        long z = state + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }
}
