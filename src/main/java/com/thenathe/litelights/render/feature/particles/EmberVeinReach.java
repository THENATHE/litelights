package com.thenathe.litelights.render.feature.particles;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterable;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;

/**
 * Which blocks show ember veins: flammable blocks joined to a fire, directly or through a chain of
 * touching flammable blocks, counted in steps from the nearest fire. Whether a reached block glows
 * is decided by a fixed hash of its position against a chance that falls with the steps, so the
 * pattern is the same every time it is worked out and never flickers.
 *
 * <p>Pure logic over packed block positions ({@link BlockPos#asLong}); the world is only asked
 * whether a block conducts, so the rules can be tested on their own.
 */
final class EmberVeinReach {
    /** Chance of a vein by steps from the fire; index 1 is a block touching the fire. */
    private static final float[] CHANCE_BY_STEPS = {0.0f, 0.9f, 0.62f, 0.36f, 0.14f};
    /** The furthest step that can glow; the search goes no further. Step 5 is already 0. */
    static final int MAX_STEPS = CHANCE_BY_STEPS.length - 1;
    /** The search stops after reaching this many blocks, so a burning forest stays cheap. */
    static final int REACH_CAP = 2048;
    private static final long HASH_SALT = 0x5DEECE66DL;
    // Face steps in Direction's order: down, up, north, south, west, east.
    static final int[] FACE_X = {0, 0, 0, 0, -1, 1};
    static final int[] FACE_Y = {-1, 1, 0, 0, 0, 0};
    static final int[] FACE_Z = {0, 0, -1, 1, 0, 0};

    /** Whether a block lets the veins through: a flammable block in the game's own table. */
    interface Conductor {
        boolean conducts(long position);
    }

    private EmberVeinReach() {
    }

    /** The chance of a vein on a block this many steps from the fire. */
    static float chance(int steps) {
        return steps <= 0 || steps > MAX_STEPS ? 0.0f : CHANCE_BY_STEPS[steps];
    }

    /** A fixed value from 0 (inclusive) to 1 (exclusive) for a block position. */
    static float hash(long position) {
        // SplitMix64's finaliser: every input bit reaches every output bit, so neighbours differ.
        long bits = position ^ HASH_SALT;
        bits = (bits ^ (bits >>> 30)) * 0xBF58476D1CE4E5B9L;
        bits = (bits ^ (bits >>> 27)) * 0x94D049BB133111EBL;
        bits ^= bits >>> 31;
        return (bits >>> 40) / (float) (1L << 24);
    }

    /** Whether a block this many steps from the fire glows. */
    static boolean shows(long position, int steps) {
        return hash(position) < chance(steps);
    }

    /**
     * Walks out from the fires through touching flammable blocks (the six faces only), up to
     * {@link #MAX_STEPS} steps and {@link #REACH_CAP} blocks. Writes the steps of every reached
     * flammable block into {@code reached}, which is cleared first; fires themselves aren't in it.
     *
     * @return how many blocks were reached
     */
    static int search(LongIterable fires, Conductor conductor, Long2IntOpenHashMap reached, LongArrayFIFOQueue queue) {
        reached.clear();
        reached.defaultReturnValue(-1);
        queue.clear();
        // Fires go in at step 0 to mark them seen; they are taken out again at the end.
        for (LongIterator iterator = fires.iterator(); iterator.hasNext(); ) {
            long fire = iterator.nextLong();
            if (reached.putIfAbsent(fire, 0) == -1) {
                queue.enqueue(fire);
            }
        }
        int fireCount = reached.size();
        int count = 0;
        while (!queue.isEmpty() && count < REACH_CAP) {
            long position = queue.dequeueLong();
            int next = reached.get(position) + 1;
            if (next > MAX_STEPS) {
                continue;
            }
            for (int face = 0; face < 6 && count < REACH_CAP; face++) {
                long neighbour = BlockPos.offset(position, FACE_X[face], FACE_Y[face], FACE_Z[face]);
                if (reached.containsKey(neighbour) || !conductor.conducts(neighbour)) {
                    continue;
                }
                reached.put(neighbour, next);
                queue.enqueue(neighbour);
                count++;
            }
        }
        if (fireCount > 0) {
            for (LongIterator iterator = fires.iterator(); iterator.hasNext(); ) {
                reached.remove(iterator.nextLong());
            }
        }
        return count;
    }
}
