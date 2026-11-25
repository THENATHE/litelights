package com.thenathe.litelights.render.feature.weather.snow;

import com.thenathe.litelights.api.SnowPoints;

/**
 * How many layers of snow LiteLights draws on a column: the rule Almanac's snow follows
 * ({@code SnowLayers.layersAt}), on the shares Almanac gives for each layer. A column holds one layer
 * for each layer whose share is past the column's point, so a column with a second layer always has a
 * first, the 90 % overlap between layers is whatever the shares say, and the thaw takes the layers
 * away in the same order. Plain arithmetic, unit-tested against Almanac's own class.
 *
 * <p>The shares LiteLights draws by are held to {@link #STEPS} steps ({@link #quantize}), so chunk
 * meshes are built again only when a share passes a step, not every tick of a snowfall: a column
 * follows the real snow to within a thirty-second of its share. Which sections a step reaches is
 * worked out from the point buckets ({@link #bucket}, {@link #changedBuckets}).
 *
 * <p>Points are LiteLights' own: Almanac's come from the world seed, which a client doesn't have.
 * Like Almanac's they are evenly spread and unrelated to the neighbours' ({@link #point}), so the
 * fake snow comes on scattered single columns as the real snow does, and the share of columns that
 * hold a layer is that layer's share: it matches the real snow in look and amount, not column for
 * column.
 */
public final class SnowLayerRule {
    /** Steps a share is held to, and buckets of points: 32, so 32 bits say which buckets changed. */
    public static final int STEPS = SnowPoints.STEPS;
    /** The most layers LiteLights draws: a full block of snow layers, as Almanac's {@code MOST_LAYERS}. */
    public static final int MOST_LAYERS = 8;

    private SnowLayerRule() {
    }

    /** How many layers lie on a column whose point is {@code point}: one for each share past it. */
    public static int layersAt(float[] shares, double point) {
        int layers = 0;
        while (layers < shares.length && point < shares[layers]) {
            layers++;
        }
        return layers;
    }

    /** A share held to the step at or below it, so a column never shows snow the real snow hasn't reached. */
    public static float quantize(double share) {
        return SnowPoints.quantize(share);
    }

    /** Which of the {@link #STEPS} buckets a point falls in. */
    public static int bucket(double point) {
        return Math.clamp((int) (point * STEPS), 0, STEPS - 1);
    }

    /**
     * The buckets whose columns can show a different number of layers after the shares change from
     * {@code before} to {@code after} (both already held to steps), as a bit per bucket. A column's
     * count changes only where some layer's share moved past its point, so the buckets between the old
     * and the new share of each layer.
     */
    public static int changedBuckets(float[] before, float[] after) {
        int changed = 0;
        int layers = Math.max(before.length, after.length);
        for (int layer = 0; layer < layers; layer++) {
            float from = layer < before.length ? before[layer] : 0.0f;
            float to = layer < after.length ? after[layer] : 0.0f;
            if (from == to) {
                continue;
            }
            int low = Math.round(Math.min(from, to) * STEPS);
            int high = Math.round(Math.max(from, to) * STEPS);
            for (int bucket = low; bucket < high && bucket < STEPS; bucket++) {
                changed |= 1 << bucket;
            }
        }
        return changed;
    }

    /**
     * A column's point, in [0, 1): evenly spread over columns and unrelated to its neighbours'. The
     * same for every block of the column, so every surface on it shows the same number of layers,
     * and the same far away (SnowPoints, which far terrain's shaders repeat).
     */
    public static double point(int x, int z) {
        return SnowPoints.point(x, z);
    }

    /** SplitMix64's finaliser: every input bit reaches every output bit. */
    static long mix(long value) {
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
}
