package com.thenathe.litelights.render.feature.water.wake;

import java.util.Arrays;

/**
 * What disturbs the water this tick, handed from the client thread to the {@link WakeField}'s step.
 * Positions are world block coordinates (x, z); the field turns them into its own cells. No
 * Minecraft types, and a fixed capacity so nothing is allocated per tick.
 *
 * <ul>
 *   <li>A <b>hull</b> is something moving through the surface: a boat or a swimmer. It is a capsule
 *   along its heading, moved from where it was last tick to where it is now, pressing the water down
 *   by {@code depth} and lifting a little ahead of its bow; it leaves foam along its sides and in its
 *   trail.</li>
 *   <li>A <b>ring</b> is a single push down, from something falling into the water: it spreads as a
 *   ring of ripples, with a ring of foam where it went in.</li>
 * </ul>
 */
public final class WakeSourceList {
    public static final int CAPACITY = 96;

    static final int HULL = 0;
    static final int RING = 1;

    private static final int FIELDS = 12;

    private final double[] values = new double[CAPACITY * FIELDS];
    private final int[] kinds = new int[CAPACITY];
    private int count;

    public void clear() {
        count = 0;
    }

    public int size() {
        return count;
    }

    public boolean isFull() {
        return count == CAPACITY;
    }

    /**
     * A hull moved from (fromX, fromZ) to (toX, toZ) this tick, facing (headingX, headingZ) (a unit
     * vector). {@code halfLength} and {@code radius} are in blocks: the capsule's axis runs
     * halfLength either way from its centre. {@code depth} is how far it presses the water down
     * (0 to about 1), {@code bowLift} how much water it heaps up ahead, {@code foam} how much foam it
     * leaves along its sides and behind (0 to 1).
     */
    public void addHull(double fromX, double fromZ, double toX, double toZ, double headingX, double headingZ,
                        double halfLength, double radius, double depth, double bowLift, double foam) {
        int base = add(HULL);
        if (base < 0) {
            return;
        }
        values[base] = fromX;
        values[base + 1] = fromZ;
        values[base + 2] = toX;
        values[base + 3] = toZ;
        values[base + 4] = headingX;
        values[base + 5] = headingZ;
        values[base + 6] = halfLength;
        values[base + 7] = radius;
        values[base + 8] = depth;
        values[base + 9] = bowLift;
        values[base + 10] = foam;
    }

    /** Something went into the water at (x, z): a push down of {@code depth} over {@code radius} blocks. */
    public void addRing(double x, double z, double radius, double depth, double foam) {
        int base = add(RING);
        if (base < 0) {
            return;
        }
        values[base] = x;
        values[base + 1] = z;
        values[base + 2] = x;
        values[base + 3] = z;
        values[base + 7] = radius;
        values[base + 8] = depth;
        values[base + 10] = foam;
    }

    /** Copies another list's sources, for the worker's own copy. */
    public void copyFrom(WakeSourceList other) {
        count = other.count;
        System.arraycopy(other.kinds, 0, kinds, 0, count);
        System.arraycopy(other.values, 0, values, 0, count * FIELDS);
    }

    int kind(int source) {
        return kinds[source];
    }

    double fromX(int source) {
        return values[source * FIELDS];
    }

    double fromZ(int source) {
        return values[source * FIELDS + 1];
    }

    double toX(int source) {
        return values[source * FIELDS + 2];
    }

    double toZ(int source) {
        return values[source * FIELDS + 3];
    }

    double headingX(int source) {
        return values[source * FIELDS + 4];
    }

    double headingZ(int source) {
        return values[source * FIELDS + 5];
    }

    double halfLength(int source) {
        return values[source * FIELDS + 6];
    }

    double radius(int source) {
        return values[source * FIELDS + 7];
    }

    double depth(int source) {
        return values[source * FIELDS + 8];
    }

    double bowLift(int source) {
        return values[source * FIELDS + 9];
    }

    double foam(int source) {
        return values[source * FIELDS + 10];
    }

    private int add(int kind) {
        if (count == CAPACITY) {
            return -1;
        }
        int base = count * FIELDS;
        Arrays.fill(values, base, base + FIELDS, 0.0);
        kinds[count] = kind;
        count++;
        return base;
    }
}
