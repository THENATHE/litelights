package com.thenathe.litelights.render.feature.weather.snow;

import java.util.Arrays;

/**
 * The season's snow as the visual snow draws it, held to {@link SnowLayerRule#STEPS} steps: per
 * area, the share of open ground each layer covers. With Almanac an area is a base climate (hot,
 * temperate, cold: one reading per climate, as Almanac keeps them) and the shares are Almanac's
 * {@code snowLayers}; without it there is one area and one layer, whose share is measured from the
 * loaded land ({@link SnowShareMeter}). Immutable, so mesh threads read it as it was published.
 */
public final class SnowShares {
    /** Areas: Almanac's three base climates, in the order of its {@code Climate} enum. */
    public static final int AREAS = 3;
    public static final SnowShares NONE = new SnowShares(new float[AREAS][0]);

    private final float[][] shares;

    private SnowShares(float[][] shares) {
        this.shares = shares;
    }

    /** Shares already held to steps, per area and layer (layer 1 first). */
    public static SnowShares of(float[][] shares) {
        float[][] copy = new float[AREAS][];
        for (int area = 0; area < AREAS; area++) {
            float[] layers = area < shares.length && shares[area] != null ? shares[area] : new float[0];
            copy[area] = Arrays.copyOf(layers, Math.min(layers.length, SnowLayerRule.MOST_LAYERS));
        }
        return new SnowShares(copy);
    }

    /** One layer everywhere, on this share of open ground (no Almanac: vanilla lays one layer). */
    public static SnowShares oneLayer(double share) {
        float held = SnowLayerRule.quantize(share);
        float[][] layers = new float[AREAS][];
        for (int area = 0; area < AREAS; area++) {
            layers[area] = new float[] {held};
        }
        return new SnowShares(layers);
    }

    /** The same layer shares in every area (QA, and one-layer measuring). */
    public static SnowShares everywhere(float... layerShares) {
        float[][] layers = new float[AREAS][];
        for (int area = 0; area < AREAS; area++) {
            layers[area] = layerShares.clone();
        }
        return of(layers);
    }

    public int layersAt(int area, double point) {
        return area >= 0 && area < AREAS ? SnowLayerRule.layersAt(shares[area], point) : 0;
    }

    /** Whether the season has any snow on this area's open ground at all. */
    public boolean seasonal(int area) {
        return area >= 0 && area < AREAS && shares[area].length > 0 && shares[area][0] > 0.0f;
    }

    /** Whether some area has the season's snow in one and none in the other. */
    public boolean seasonalChanged(SnowShares other) {
        for (int area = 0; area < AREAS; area++) {
            if (seasonal(area) != other.seasonal(area)) {
                return true;
            }
        }
        return false;
    }

    /** The first layer's share in an area, 0 to 1. */
    public float cover(int area) {
        return seasonal(area) ? shares[area][0] : 0.0f;
    }

    /** The deepest any area's snow lies on some column, in layers. */
    public int deepest() {
        int deepest = 0;
        for (float[] layers : shares) {
            int started = 0;
            while (started < layers.length && layers[started] > 0.0f) {
                started++;
            }
            deepest = Math.max(deepest, started);
        }
        return deepest;
    }

    /** Per area, the point buckets whose columns show a different number of layers in {@code after}. */
    public int[] changedBuckets(SnowShares after) {
        int[] changed = new int[AREAS];
        for (int area = 0; area < AREAS; area++) {
            changed[area] = SnowLayerRule.changedBuckets(shares[area], after.shares[area]);
        }
        return changed;
    }

    float[] sharesOf(int area) {
        return Arrays.copyOf(shares[area], shares[area].length);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SnowShares that && Arrays.deepEquals(shares, that.shares);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(shares);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (int area = 0; area < AREAS; area++) {
            text.append(area == 0 ? "" : " | ").append(Arrays.toString(shares[area]));
        }
        return text.toString();
    }
}
