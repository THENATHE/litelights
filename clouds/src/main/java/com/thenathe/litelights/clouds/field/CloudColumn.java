package com.thenathe.litelights.clouds.field;

/**
 * What the field says about one column of sky: up to {@link #MAX_SPANS} spans of cloud, each a
 * bottom and a top in world y, with how solid it is, how stormy and what type, and what falls from
 * it. Each formation over the column gives at most one span (a thunderstorm two: its tower and its
 * anvil), sorted by their bottoms. Spans of different formations may overlap (a tower rising
 * through a rain deck); at any height the densest span covering it is the one that counts
 * ({@link #densityAt}, {@link #spanAt}). They are kept apart rather than joined so that each keeps
 * its own density: a formation growing in at a deck's edge then can't stretch the deck in a step.
 *
 * <p>Reused from column to column, so filling it allocates nothing.
 */
public final class CloudColumn {
    public static final int MAX_SPANS = 6;

    // One more slot than allowed, so a span can be put in before the faintest is dropped.
    private final float[] bottoms = new float[MAX_SPANS + 1];
    private final float[] tops = new float[MAX_SPANS + 1];
    private final float[] densities = new float[MAX_SPANS + 1];
    private final float[] storminess = new float[MAX_SPANS + 1];
    private final CloudType[] types = new CloudType[MAX_SPANS + 1];
    private int count;
    private Precipitation precipitation = Precipitation.NONE;
    private float precipitationRate;

    public int spanCount() {
        return count;
    }

    /** The span's bottom, in world y. */
    public float bottom(int span) {
        return bottoms[span];
    }

    /** The span's top, in world y. */
    public float top(int span) {
        return tops[span];
    }

    /**
     * How solid the span is, 0..1: 1 in a cloud's body, falling toward 0 in its thin fringe, so a
     * mesher can draw solid cubes over about a half and see-through ones below.
     */
    public float density(int span) {
        return densities[span];
    }

    /** How stormy, 0..1: how dark its underside should be. Fades with the density at the fringe. */
    public float storminess(int span) {
        return storminess[span];
    }

    public CloudType type(int span) {
        return types[span];
    }

    /** What falls from this column's clouds. */
    public Precipitation precipitation() {
        return precipitation;
    }

    /** How hard it falls, 0..1. */
    public float precipitationRate() {
        return precipitationRate;
    }

    public boolean isClear() {
        return count == 0;
    }

    /** The densest span's density: how cloudy this column looks from far above or below. */
    public float cover() {
        float cover = 0.0f;
        for (int i = 0; i < count; i++) {
            cover = Math.max(cover, densities[i]);
        }
        return cover;
    }

    /** The highest top, or NaN for a clear column. */
    public float highestTop() {
        if (count == 0) {
            return Float.NaN;
        }
        float highest = tops[0];
        for (int i = 1; i < count; i++) {
            highest = Math.max(highest, tops[i]);
        }
        return highest;
    }

    /** The lowest bottom, or NaN for a clear column. */
    public float lowestBottom() {
        return count == 0 ? Float.NaN : bottoms[0];
    }

    /** The height of sky holding any cloud, overlaps counted once, in blocks. */
    public float thickness() {
        float thickness = 0.0f;
        float coveredTo = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            float from = Math.max(bottoms[i], coveredTo);
            if (tops[i] > from) {
                thickness += tops[i] - from;
            }
            coveredTo = Math.max(coveredTo, tops[i]);
        }
        return thickness;
    }

    /** How solid the cloud is at height {@code y}: the densest span there, or 0. */
    public float densityAt(float y) {
        int span = spanAt(y);
        return span < 0 ? 0.0f : densities[span];
    }

    /** The densest span holding height {@code y}, or -1. */
    public int spanAt(float y) {
        int densest = -1;
        for (int i = 0; i < count && bottoms[i] <= y; i++) {
            if (y <= tops[i] && (densest < 0 || densities[i] > densities[densest])) {
                densest = i;
            }
        }
        return densest;
    }

    void reset() {
        count = 0;
        precipitation = Precipitation.NONE;
        precipitationRate = 0.0f;
    }

    /**
     * Puts in a span, keeping them sorted by bottom. Spans with no height are left out; past
     * {@link #MAX_SPANS} the faintest (least density times height) is dropped.
     */
    void addSpan(float bottom, float top, float density, float storm, CloudType type) {
        if (!(top > bottom) || !(density > 0.0f)) {
            return;
        }
        int index = count;
        while (index > 0 && bottoms[index - 1] > bottom) {
            copy(index - 1, index);
            index--;
        }
        bottoms[index] = bottom;
        tops[index] = top;
        densities[index] = density;
        storminess[index] = storm;
        types[index] = type;
        count++;
        if (count > MAX_SPANS) {
            dropFaintest();
        }
    }

    /**
     * Holds every span at or above the floor: a base below it is raised to it, and a span wholly
     * below it goes. Raising bottoms keeps their order.
     */
    void floorAt(float floor) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (tops[i] <= floor) {
                continue;
            }
            if (kept != i) {
                copy(i, kept);
            }
            bottoms[kept] = Math.max(bottoms[kept], floor);
            kept++;
        }
        count = kept;
    }

    /** Keeps whatever falls hardest. */
    void addPrecipitation(Precipitation kind, float rate) {
        if (kind != Precipitation.NONE && rate > precipitationRate) {
            precipitation = kind;
            precipitationRate = rate;
        }
    }

    private void dropFaintest() {
        int faintest = 0;
        for (int i = 1; i < count; i++) {
            if (weight(i) < weight(faintest)) {
                faintest = i;
            }
        }
        for (int j = faintest; j < count - 1; j++) {
            copy(j + 1, j);
        }
        count--;
    }

    private float weight(int span) {
        return densities[span] * (tops[span] - bottoms[span]);
    }

    private void copy(int from, int to) {
        bottoms[to] = bottoms[from];
        tops[to] = tops[from];
        densities[to] = densities[from];
        storminess[to] = storminess[from];
        types[to] = types[from];
    }
}
