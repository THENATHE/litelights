package com.thenathe.litelights.render.feature.water;

import java.util.Arrays;

/**
 * Where the shore is, seen from every block column near the camera, for the ocean waves (Ocean
 * waves, Experimental): how far the column is from the waterline, which way the shore lies, and how
 * much ocean swell its water gets. Worked out from a copy of the {@link FluidColumnGrid}'s window
 * and the {@link OceanColumns}, off the render thread, and uploaded as a 256 x 256 region of the
 * fluid data texture under the rain tops and the glass windows (from y 640), so the waves need no
 * texture unit of their own.
 *
 * <p>Texel bytes (shaders/include/water/common.glsl, {@code lite_shore_field}):
 * <ul>
 *   <li>R: the signed distance from the column's centre to the waterline in quarter blocks, offset
 *       by 8 blocks: positive out on the water, negative up the beach. Between column centres the
 *       shader blends it, so the waterline is where the blend crosses 0.</li>
 *   <li>G, B: the direction toward the shore (x, z) as signed bytes, the way waves come in: down the
 *       distance's slope, on the water and up the beach alike.</li>
 *   <li>A: how much ocean swell the column gets, 0 to 255. Water: its own; a beach column at the
 *       waterline that the swash can reach from the water across other such columns: that of the
 *       water beside it; everything else 0.</li>
 * </ul>
 *
 * <p>Distances are Euclidean, from a two-pass propagation of each column's nearest source
 * (near-exact, a fraction of a block off at worst). No Minecraft types here, so it is unit-tested.
 * One instance holds the scratch arrays for one computation at a time.
 */
public final class ShoreField {
    public static final int SIZE = FluidColumnGrid.SIZE;
    /** Quarter-block steps of the distance byte, and the distance its zero stands for. */
    public static final float DISTANCE_STEPS = 4.0f;
    public static final float DISTANCE_OFFSET = 8.0f;
    public static final float FARTHEST = (255.0f / DISTANCE_STEPS) - DISTANCE_OFFSET;

    /** Land this close to the water (in blocks) is thin: a post, a pier, a wall. Waves pass it by. */
    static final float THIN_LAND = 2.0f;
    /** The swash reaches at most this far up a beach; columns further inland get no wave data. */
    static final float BEACH_REACH = 4.0f;
    /** A beach takes the swash only when its top is this close to the water's surface (1/64 blocks). */
    private static final int BEACH_BELOW = FluidColumnGrid.HEIGHT_STEPS_PER_BLOCK / 2;
    private static final int BEACH_ABOVE = FluidColumnGrid.HEIGHT_STEPS_PER_BLOCK * 5 / 8;
    /** Dry land this far below the water beside it is a drop the water runs over, not a shore. */
    private static final int BELOW_WATERLINE = FluidColumnGrid.HEIGHT_STEPS_PER_BLOCK / 2;
    /** How the swell's strength is smoothed across biome borders and around posts: a box this many columns wide. */
    private static final int STRENGTH_BLUR = 9;
    /** And the distance, before the shore's direction is read from its slope. */
    private static final int DIRECTION_BLUR = 5;
    private static final float DIRECTION_CAP = 24.0f;

    private static final int N = SIZE;
    private static final int CELLS = N * N;
    private static final int NONE = -1;

    private final boolean[] water = new boolean[CELLS];
    private final boolean[] land = new boolean[CELLS];
    private final boolean[] mask = new boolean[CELLS];
    private final boolean[] reached = new boolean[CELLS];
    private final int[] nearestWater = new int[CELLS];
    private final int[] nearest = new int[CELLS];
    private final float[] intoLand = new float[CELLS];
    private final float[] distance = new float[CELLS];
    private final float[] strength = new float[CELLS];
    private final float[] scratch = new float[CELLS];
    private final float[] smooth = new float[CELLS];
    private final float[] weights = new float[CELLS];

    /**
     * Works the field out for one window.
     *
     * @param columns the grid's columns in window order (index x + z * SIZE from the window's corner)
     * @param kinds   the {@link OceanColumns} bits in the same order
     * @param texels  receives the texels in the same order, as 0xAABBGGRR
     */
    public void compute(int[] columns, byte[] kinds, int[] texels) {
        classify(columns);
        // Every column's nearest water, and how far into the land a dry column lies.
        propagate(water, nearestWater);
        fillDistances(nearestWater, intoLand);
        // Thin land (posts, piers, walls) doesn't turn the waves: keep land only within THIN_LAND of
        // land at least that far from the water, so a blob must be some blocks wide to count.
        for (int i = 0; i < CELLS; i++) {
            mask[i] = land[i] && intoLand[i] >= THIN_LAND;
        }
        propagate(mask, nearest);
        fillDistances(nearest, scratch);
        for (int i = 0; i < CELLS; i++) {
            land[i] = land[i] && scratch[i] <= THIN_LAND + 0.01f;
        }
        signedDistances();
        swellStrength(columns, kinds);
        directionsAndTexels(columns, texels);
    }

    /**
     * Water and land columns; dry land well below the water beside it is a drop, which counts as
     * neither, and so does ice: the sea carries on under it, so it turns no waves and takes no swash,
     * and the beach behind an ice sheet is out of the swash's reach.
     */
    private void classify(int[] columns) {
        for (int i = 0; i < CELLS; i++) {
            int column = columns[i];
            boolean known = (column & FluidColumnGrid.KNOWN) != 0;
            boolean fluid = FluidColumnGrid.hasFluid(column);
            boolean lava = (column & FluidColumnGrid.LAVA) != 0;
            boolean ice = (column & FluidColumnGrid.ICE) != 0;
            water[i] = fluid && !lava;
            land[i] = known && (!fluid || lava) && !ice;
        }
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                int i = x + z * N;
                if (!land[i] || FluidColumnGrid.hasFluid(columns[i])) {
                    continue;
                }
                int highestWater = highestWaterAround(columns, x, z);
                if (highestWater != 0 && FluidColumnGrid.solidTopSteps(columns[i]) < highestWater - BELOW_WATERLINE) {
                    land[i] = false;
                }
            }
        }
    }

    private int highestWaterAround(int[] columns, int x, int z) {
        int highest = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int nz = z + dz;
                if (nx >= 0 && nz >= 0 && nx < N && nz < N && water[nx + nz * N]) {
                    highest = Math.max(highest, FluidColumnGrid.heightSteps(columns[nx + nz * N]));
                }
            }
        }
        return highest;
    }

    /**
     * Signed distance from each column's centre to the waterline: on water, to the nearest land less
     * half a block; on land, to the nearest water less half a block, negated. Columns that are
     * neither (unknown, a drop, thin land) take the water's side.
     */
    private void signedDistances() {
        propagate(land, nearest);
        fillDistances(nearest, distance);
        for (int i = 0; i < CELLS; i++) {
            distance[i] = land[i] ? -(intoLand[i] - 0.5f) : distance[i] - 0.5f;
        }
    }

    /**
     * How much ocean swell each column gets. Water in an ocean or beach biome gets it within reach of
     * water at least five blocks from any shore (so a pond in an ocean biome stays calm); water
     * anywhere else only near water fourteen blocks from any shore (a big lake, never a river or a
     * pond). Flowing water gets none. Smoothed so biome borders and posts leave no edge. A beach
     * column at the water's level takes the strength of the water beside it.
     */
    private void swellStrength(int[] columns, byte[] kinds) {
        markWaterAtLeast(5.0f);
        propagate(mask, nearest);
        fillDistances(nearest, scratch);
        for (int i = 0; i < CELLS; i++) {
            boolean ocean = (kinds[i] & OceanColumns.OCEAN_BIOME) != 0;
            strength[i] = ocean ? 1.0f - smoothstep(10.0f, 24.0f, scratch[i]) : 0.0f;
        }
        markWaterAtLeast(14.0f);
        propagate(mask, nearest);
        fillDistances(nearest, scratch);
        for (int i = 0; i < CELLS; i++) {
            boolean still = (kinds[i] & OceanColumns.STILL) != 0;
            boolean scanned = (kinds[i] & OceanColumns.SCANNED) != 0;
            float open = 1.0f - smoothstep(8.0f, 24.0f, scratch[i]);
            strength[i] = water[i] && still ? Math.max(strength[i], open) : 0.0f;
            // Covered water (under a pier) wasn't scanned for its kind: it takes its neighbours' swell.
            weights[i] = water[i] && scanned ? 1.0f : 0.0f;
        }
        // A weighted box blur over the water only, so the shore doesn't thin the swell beside it.
        for (int i = 0; i < CELLS; i++) {
            scratch[i] = strength[i];
        }
        boxBlur(scratch, STRENGTH_BLUR);
        boxBlur(weights, STRENGTH_BLUR);
        for (int i = 0; i < CELLS; i++) {
            smooth[i] = weights[i] > 1.0e-4f ? scratch[i] / weights[i] : 0.0f;
        }
        for (int i = 0; i < CELLS; i++) {
            if (water[i]) {
                strength[i] = smooth[i];
            } else if (land[i]) {
                strength[i] = beachStrength(columns, i);
            } else {
                // Thin land and drops sit among the water: give them its swell, so waves pass a post
                // without a calm patch around it. Unknown columns get none.
                strength[i] = (columns[i] & FluidColumnGrid.KNOWN) != 0 ? smooth[i] : 0.0f;
            }
        }
        keepReachableBeach();
    }

    /**
     * The swash runs up from the water across the beach, so a beach column keeps its strength only
     * when a neighbour nearer the water (water, or a beach column that keeps its own) leads to it.
     * Low ground behind a sea wall, a raised path or a step gets none, however far the waves run.
     * Only strictly nearer neighbours count, diagonals included, so the swash wraps round a post but
     * never creeps sideways along the back of a wall.
     */
    private void keepReachableBeach() {
        for (int i = 0; i < CELLS; i++) {
            reached[i] = water[i];
        }
        // Each pass reaches at least one more column up the beach, which ends BEACH_REACH from the
        // water, so a handful of passes settle it; the cap only guards against a mistake.
        boolean changed = true;
        for (int pass = 0; changed && pass < 16; pass++) {
            changed = false;
            for (int z = 0; z < N; z++) {
                for (int x = 0; x < N; x++) {
                    int i = x + z * N;
                    if (!reached[i] && land[i] && strength[i] > 0.0f && reachedFromNearer(x, z)) {
                        reached[i] = true;
                        changed = true;
                    }
                }
            }
        }
        for (int i = 0; i < CELLS; i++) {
            if (land[i] && !reached[i]) {
                strength[i] = 0.0f;
            }
        }
    }

    private boolean reachedFromNearer(int x, int z) {
        float own = intoLand[x + z * N];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int nz = z + dz;
                if ((dx != 0 || dz != 0) && nx >= 0 && nz >= 0 && nx < N && nz < N) {
                    int neighbour = nx + nz * N;
                    if (reached[neighbour] && intoLand[neighbour] < own) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The swell of the water beside a beach column, when the beach's top is at that water's level. */
    private float beachStrength(int[] columns, int i) {
        int source = nearestWater[i];
        if (source == NONE || -distance[i] > BEACH_REACH) {
            return 0.0f;
        }
        int above = FluidColumnGrid.solidTopSteps(columns[i]) - FluidColumnGrid.heightSteps(columns[source]);
        if (above < -BEACH_BELOW || above > BEACH_ABOVE) {
            return 0.0f;
        }
        return smooth[source];
    }

    private void markWaterAtLeast(float blocks) {
        for (int i = 0; i < CELLS; i++) {
            mask[i] = water[i] && distance[i] >= blocks;
        }
    }

    /**
     * The direction toward the shore, from the slope of a smoothed copy of the distance (smoothed so
     * the corners of the coast and the ridge between two shores turn the waves gradually), and the
     * texels.
     */
    private void directionsAndTexels(int[] columns, int[] texels) {
        for (int i = 0; i < CELLS; i++) {
            smooth[i] = Math.clamp(distance[i], -DISTANCE_OFFSET, DIRECTION_CAP);
        }
        boxBlur(smooth, DIRECTION_BLUR);
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                int i = x + z * N;
                float gradientX = (smooth[Math.min(x + 1, N - 1) + z * N] - smooth[Math.max(x - 1, 0) + z * N]) * 0.5f;
                float gradientZ = (smooth[x + Math.min(z + 1, N - 1) * N] - smooth[x + Math.max(z - 1, 0) * N]) * 0.5f;
                float length = (float) Math.sqrt(gradientX * gradientX + gradientZ * gradientZ);
                float towardX = 0.0f;
                float towardZ = 0.0f;
                if (length > 0.05f) {
                    towardX = -gradientX / length;
                    towardZ = -gradientZ / length;
                }
                boolean anything = strength[i] > 0.0f && (columns[i] & FluidColumnGrid.KNOWN) != 0;
                texels[i] = anything ? texel(distance[i], towardX, towardZ, strength[i]) : texel(distance[i], 0.0f, 0.0f, 0.0f);
            }
        }
    }

    /** One texel, as 0xAABBGGRR. */
    public static int texel(float signedDistance, float towardX, float towardZ, float swell) {
        int red = Math.clamp(Math.round((signedDistance + DISTANCE_OFFSET) * DISTANCE_STEPS), 0, 255);
        int green = Math.clamp(Math.round(towardX * 127.0f), -127, 127) & 0xFF;
        int blue = Math.clamp(Math.round(towardZ * 127.0f), -127, 127) & 0xFF;
        int alpha = Math.clamp(Math.round(swell * 255.0f), 0, 255);
        return red | green << 8 | blue << 16 | alpha << 24;
    }

    /** The signed distance a texel holds, in blocks (as the shader decodes it). */
    public static float distance(int texel) {
        return (texel & 0xFF) / DISTANCE_STEPS - DISTANCE_OFFSET;
    }

    public static float towardX(int texel) {
        return (byte) (texel >> 8) / 127.0f;
    }

    public static float towardZ(int texel) {
        return (byte) (texel >> 16) / 127.0f;
    }

    public static float swell(int texel) {
        return (texel >>> 24) / 255.0f;
    }

    // --- Distance propagation ---

    /**
     * Every cell's nearest source cell (or NONE when there is none), by passing each cell's nearest
     * on to its neighbours forward and then backward over the window, the way an 8-neighbour
     * Euclidean distance transform does.
     */
    private static void propagate(boolean[] sources, int[] nearest) {
        for (int i = 0; i < CELLS; i++) {
            nearest[i] = sources[i] ? i : NONE;
        }
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                int i = x + z * N;
                if (nearest[i] == i) {
                    continue;
                }
                int best = nearest[i];
                best = closer(x, z, best, x > 0 ? nearest[i - 1] : NONE);
                best = closer(x, z, best, z > 0 ? nearest[i - N] : NONE);
                best = closer(x, z, best, x > 0 && z > 0 ? nearest[i - N - 1] : NONE);
                best = closer(x, z, best, x < N - 1 && z > 0 ? nearest[i - N + 1] : NONE);
                nearest[i] = best;
            }
            for (int x = N - 2; x >= 0; x--) {
                int i = x + z * N;
                if (nearest[i] != i) {
                    nearest[i] = closer(x, z, nearest[i], nearest[i + 1]);
                }
            }
        }
        for (int z = N - 1; z >= 0; z--) {
            for (int x = N - 1; x >= 0; x--) {
                int i = x + z * N;
                if (nearest[i] == i) {
                    continue;
                }
                int best = nearest[i];
                best = closer(x, z, best, x < N - 1 ? nearest[i + 1] : NONE);
                best = closer(x, z, best, z < N - 1 ? nearest[i + N] : NONE);
                best = closer(x, z, best, x < N - 1 && z < N - 1 ? nearest[i + N + 1] : NONE);
                best = closer(x, z, best, x > 0 && z < N - 1 ? nearest[i + N - 1] : NONE);
                nearest[i] = best;
            }
            for (int x = 1; x < N; x++) {
                int i = x + z * N;
                if (nearest[i] != i) {
                    nearest[i] = closer(x, z, nearest[i], nearest[i - 1]);
                }
            }
        }
    }

    private static int closer(int x, int z, int current, int candidate) {
        if (candidate == NONE) {
            return current;
        }
        if (current == NONE) {
            return candidate;
        }
        return squaredDistance(x, z, candidate) < squaredDistance(x, z, current) ? candidate : current;
    }

    private static int squaredDistance(int x, int z, int cell) {
        int dx = x - cell % N;
        int dz = z - cell / N;
        return dx * dx + dz * dz;
    }

    private static void fillDistances(int[] nearest, float[] out) {
        for (int i = 0; i < CELLS; i++) {
            out[i] = nearest[i] == NONE ? Float.MAX_VALUE : (float) Math.sqrt(squaredDistance(i % N, i / N, nearest[i]));
        }
    }

    // --- Smoothing ---

    /** A box blur of {@code width} cells (odd), rows then columns, the window's edges held. */
    private void boxBlur(float[] values, int width) {
        int half = width / 2;
        float[] line = new float[N + 2 * half];
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N + 2 * half; b++) {
                    int along = Math.clamp(b - half, 0, N - 1);
                    line[b] = values[pass == 0 ? along + a * N : a + along * N];
                }
                float sum = 0.0f;
                for (int b = 0; b < width - 1; b++) {
                    sum += line[b];
                }
                for (int b = 0; b < N; b++) {
                    sum += line[b + width - 1];
                    values[pass == 0 ? b + a * N : a + b * N] = sum / width;
                    sum -= line[b];
                }
            }
        }
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /** Clears an array of texels to "nothing here" (used before the first field is ready). */
    public static void clear(int[] texels) {
        Arrays.fill(texels, texel(FARTHEST, 0.0f, 0.0f, 0.0f));
    }
}
