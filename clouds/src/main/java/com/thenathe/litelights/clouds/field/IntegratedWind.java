package com.thenathe.litelights.clouds.field;

import java.util.Arrays;

/**
 * The drift of a wind given only as its velocity at any tick (a weather mod's prevailing wind,
 * which wanders and has no closed form): the velocity summed from tick 0, so every player who asks
 * about the same tick gets the same drift without accumulating anything of their own.
 *
 * <p>Time is cut into segments of {@link #SEGMENT_TICKS}; each is summed by eight-point
 * Gauss-Legendre quadrature, which is exact to rounding for a wind that changes over days, and the
 * running totals at the segments' ends are kept. A whole tick inside a segment adds one more
 * quadrature over the part up to it, and a fraction of a tick is drawn between two whole ticks.
 * The totals are always added in the same order, so two machines agree.
 *
 * <p>Summing up to the present can take a while in an old world (about 8,300 segments, 67,000 asks
 * of the wind, for each hundred million ticks), so {@link #prepare} does it ahead, on a worker.
 * Safe from any thread.
 */
public final class IntegratedWind implements CloudDrift {
    /** The length of a segment, in ticks: half a day. */
    static final double SEGMENT_TICKS = 12_000.0;
    private static final double[] NODES = {
        -0.9602898564975363, -0.7966664774136267, -0.5255324099163290, -0.1834346424956498,
        0.1834346424956498, 0.5255324099163290, 0.7966664774136267, 0.9602898564975363};
    private static final double[] WEIGHTS = {
        0.1012285362903763, 0.2223810344533745, 0.3137066458778873, 0.3626837833783620,
        0.3626837833783620, 0.3137066458778873, 0.2223810344533745, 0.1012285362903763};

    /** The wind's velocity at a tick, in blocks per tick. */
    @FunctionalInterface
    public interface Velocity {
        /** Writes the velocity at {@code tick} into {@code out}: x, then z. */
        void at(double tick, double[] out);
    }

    private final Velocity velocity;
    private final double[] sample = new double[2];
    private final double[] velocityAt = new double[2];
    /** The drift at the end of each segment so far: x and z, segment after segment. */
    private double[] totals = new double[64];
    private int segments;
    /**
     * The drift at the whole tick last asked about and at the one after it: a fraction of a tick
     * is drawn between the two, so a frame asks the wind nothing new until the next tick.
     */
    private long wholeTick = Long.MIN_VALUE;
    private double fromX;
    private double fromZ;
    private double toX;
    private double toZ;

    public IntegratedWind(Velocity velocity) {
        this.velocity = velocity;
    }

    /** Sums the wind up to {@code tick}, so later questions up to there are quick. */
    public synchronized void prepare(double tick) {
        ensure(segmentOf(tick));
    }

    /** Whether the wind has been summed up to {@code tick} already. */
    public synchronized boolean preparedTo(double tick) {
        return segments >= segmentOf(tick);
    }

    @Override
    public double driftX(double tick) {
        synchronized (this) {
            double fraction = between(tick);
            return fromX + (toX - fromX) * fraction;
        }
    }

    @Override
    public double driftZ(double tick) {
        synchronized (this) {
            double fraction = between(tick);
            return fromZ + (toZ - fromZ) * fraction;
        }
    }

    /**
     * Readies the drift at the whole ticks either side of {@code tick} and gives how far between
     * them it is. Within one tick the wind's change is far too small to bend the path.
     */
    private double between(double tick) {
        double at = Math.max(0.0, tick);
        long whole = (long) Math.floor(at);
        if (whole != wholeTick) {
            driftAt(whole);
            fromX = sample[0];
            fromZ = sample[1];
            integrate(whole, whole + 1.0);
            toX = fromX + sample[0];
            toZ = fromZ + sample[1];
            wholeTick = whole;
        }
        return at - whole;
    }

    /** The drift at a whole tick, into {@code sample}. */
    private void driftAt(long tick) {
        int segment = segmentOf(tick);
        ensure(segment);
        double startX = segment == 0 ? 0.0 : totals[2 * segment - 2];
        double startZ = segment == 0 ? 0.0 : totals[2 * segment - 1];
        integrate(segment * SEGMENT_TICKS, tick);
        sample[0] += startX;
        sample[1] += startZ;
    }

    private static int segmentOf(double tick) {
        return (int) Math.min(Integer.MAX_VALUE / 2, Math.floor(Math.max(0.0, tick) / SEGMENT_TICKS));
    }

    /** Sums every whole segment before {@code segment}. */
    private void ensure(int segment) {
        if (segments >= segment) {
            return;
        }
        if (totals.length < 2 * segment) {
            totals = Arrays.copyOf(totals, Math.max(2 * segment, totals.length * 2));
        }
        double x = segments == 0 ? 0.0 : totals[2 * segments - 2];
        double z = segments == 0 ? 0.0 : totals[2 * segments - 1];
        for (int index = segments; index < segment; index++) {
            integrate(index * SEGMENT_TICKS, (index + 1) * SEGMENT_TICKS);
            x += sample[0];
            z += sample[1];
            totals[2 * index] = x;
            totals[2 * index + 1] = z;
        }
        segments = segment;
    }

    /** The wind summed from {@code from} to {@code to}, into {@code sample}. */
    private void integrate(double from, double to) {
        double half = (to - from) * 0.5;
        double middle = from + half;
        double x = 0.0;
        double z = 0.0;
        for (int node = 0; node < NODES.length; node++) {
            velocity.at(middle + half * NODES[node], velocityAt);
            x += WEIGHTS[node] * velocityAt[0];
            z += WEIGHTS[node] * velocityAt[1];
        }
        sample[0] = x * half;
        sample[1] = z * half;
    }
}
