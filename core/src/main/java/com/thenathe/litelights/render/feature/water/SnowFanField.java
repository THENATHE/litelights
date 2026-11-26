package com.thenathe.litelights.render.feature.water;

import java.util.Arrays;

/**
 * How far snow fans in from the open under trees and overhangs (visual snow's Even snow under trees
 * and Snow under overhangs): for every covered column of bare ground, its distance along the ground
 * from the nearest open column with snow on it, turned into how much of the snow reaches it, a
 * steady falloff from all of it at the edge to none at the reach. Distances run column to column in
 * eighths of a block (8 across, 11 corner to corner, so the falloff is round rather than square) and
 * only over ground within a block's height of the last, so a dusting never climbs a wall or crosses
 * a doorway's frame into a house. A small Dijkstra over integer steps, bounded by the reach, so it
 * looks at the ground near snow and nothing else. No Minecraft types here, so it is unit-tested.
 */
public final class SnowFanField {
    /** Not snow, not covered ground: snow neither comes from it nor spreads over it. */
    public static final byte BLOCKED = 0;
    /** An open column with snow on it: where the fanning starts. */
    public static final byte SOURCE = 1;
    /** Bare ground under a canopy. */
    public static final byte UNDER_TREE = 2;
    /** Bare ground under a roof, an eave or another overhang. */
    public static final byte UNDER_OVERHANG = 3;
    /** The amount stored for all of the snow. */
    public static final int FULL = 250;
    /** How far snow fans in, in blocks: under a canopy, and under anything else. */
    public static final int TREE_REACH = 5;
    /** Four since visual-snow-117 (three before): eaves and awnings left too hard a line where the snow stopped (the owner). */
    public static final int OVERHANG_REACH = 4;

    private static final int STRAIGHT = 8;
    private static final int DIAGONAL = 11;
    private static final int[] STEP_X = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] STEP_Z = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final int[] STEP_COST = {STRAIGHT, STRAIGHT, STRAIGHT, STRAIGHT, DIAGONAL, DIAGONAL, DIAGONAL, DIAGONAL};

    private final int size;
    private final int[] distance;
    private final int[][] buckets;
    private final int[] bucketSizes;

    public SnowFanField(int size) {
        this.size = size;
        this.distance = new int[size * size];
        int longest = Math.max(TREE_REACH, OVERHANG_REACH) * STRAIGHT + 1;
        this.buckets = new int[longest][];
        this.bucketSizes = new int[longest];
        for (int bucket = 0; bucket < longest; bucket++) {
            buckets[bucket] = new int[64];
        }
    }

    /**
     * Works out the fan for a square of columns, row by row ({@code size} a row).
     *
     * @param kinds   each column's kind, one of the constants above
     * @param heights the height of each column's ground (for a source, the ground its snow lies on)
     * @param fan     filled with how much snow reaches each column, 0 to {@link #FULL}; {@link #FULL}
     *                for sources, 0 for blocked columns and covered ones out of reach
     */
    public void compute(byte[] kinds, int[] heights, byte[] fan) {
        Arrays.fill(distance, Integer.MAX_VALUE);
        Arrays.fill(bucketSizes, 0);
        for (int cell = 0; cell < kinds.length; cell++) {
            if (kinds[cell] == SOURCE && bordersCover(kinds, cell)) {
                distance[cell] = 0;
                push(0, cell);
            }
        }
        for (int bucket = 0; bucket < buckets.length; bucket++) {
            for (int entry = 0; entry < bucketSizes[bucket]; entry++) {
                int cell = buckets[bucket][entry];
                if (distance[cell] != bucket) {
                    continue;
                }
                spread(kinds, heights, cell, bucket);
            }
        }
        for (int cell = 0; cell < kinds.length; cell++) {
            fan[cell] = (byte) amount(kinds[cell], distance[cell]);
        }
    }

    /** How much of the snow reaches a column of this kind this far from the snow: a steady line down to the reach. */
    public static int amount(byte kind, int eighths) {
        if (kind == SOURCE) {
            return FULL;
        }
        if (kind == BLOCKED || eighths == Integer.MAX_VALUE) {
            return 0;
        }
        int reach = reachOf(kind) * STRAIGHT;
        return eighths >= reach ? 0 : Math.round(FULL * (1.0f - (float) eighths / reach));
    }

    private static int reachOf(byte kind) {
        return kind == UNDER_TREE ? TREE_REACH : OVERHANG_REACH;
    }

    private void spread(byte[] kinds, int[] heights, int cell, int from) {
        int x = cell % size;
        int z = cell / size;
        for (int step = 0; step < STEP_X.length; step++) {
            int nextX = x + STEP_X[step];
            int nextZ = z + STEP_Z[step];
            if (nextX < 0 || nextZ < 0 || nextX >= size || nextZ >= size) {
                continue;
            }
            int next = nextZ * size + nextX;
            byte kind = kinds[next];
            if (kind != UNDER_TREE && kind != UNDER_OVERHANG) {
                continue;
            }
            if (Math.abs(heights[next] - heights[cell]) > 1) {
                continue;
            }
            // A diagonal step must not cut a corner between two blocked columns.
            if (step >= 4 && !passable(kinds, x + STEP_X[step], z) && !passable(kinds, x, z + STEP_Z[step])) {
                continue;
            }
            int reached = from + STEP_COST[step];
            if (reached >= reachOf(kind) * STRAIGHT || reached >= distance[next]) {
                continue;
            }
            distance[next] = reached;
            push(reached, next);
        }
    }

    private boolean passable(byte[] kinds, int x, int z) {
        byte kind = kinds[z * size + x];
        return kind != BLOCKED;
    }

    private boolean bordersCover(byte[] kinds, int cell) {
        int x = cell % size;
        int z = cell / size;
        for (int step = 0; step < STEP_X.length; step++) {
            int nextX = x + STEP_X[step];
            int nextZ = z + STEP_Z[step];
            if (nextX >= 0 && nextZ >= 0 && nextX < size && nextZ < size) {
                byte kind = kinds[nextZ * size + nextX];
                if (kind == UNDER_TREE || kind == UNDER_OVERHANG) {
                    return true;
                }
            }
        }
        return false;
    }

    private void push(int bucket, int cell) {
        if (bucketSizes[bucket] == buckets[bucket].length) {
            buckets[bucket] = Arrays.copyOf(buckets[bucket], buckets[bucket].length * 2);
        }
        buckets[bucket][bucketSizes[bucket]++] = cell;
    }
}
