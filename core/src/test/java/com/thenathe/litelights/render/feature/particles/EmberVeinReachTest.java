package com.thenathe.litelights.render.feature.particles;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmberVeinReachTest {
    private final Long2IntOpenHashMap reached = new Long2IntOpenHashMap();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

    private static long at(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private int search(LongArrayList fires, LongOpenHashSet flammable) {
        return EmberVeinReach.search(fires, flammable::contains, reached, queue);
    }

    @Test
    void chanceFallsWithStepsAndEndsAtFive() {
        assertEquals(0.0f, EmberVeinReach.chance(0));
        assertEquals(0.9f, EmberVeinReach.chance(1), 1.0e-6f);
        for (int steps = 2; steps <= EmberVeinReach.MAX_STEPS; steps++) {
            assertTrue(EmberVeinReach.chance(steps) < EmberVeinReach.chance(steps - 1), "chance falls at step " + steps);
            assertTrue(EmberVeinReach.chance(steps) > 0.0f);
        }
        assertEquals(0.0f, EmberVeinReach.chance(5));
        assertEquals(0.0f, EmberVeinReach.chance(50));
    }

    @Test
    void hashIsStableAndSpreadEvenly() {
        long position = at(12, 70, -300);
        assertEquals(EmberVeinReach.hash(position), EmberVeinReach.hash(position));
        int buckets = 10;
        int[] counts = new int[buckets];
        int samples = 0;
        for (int x = -20; x < 20; x++) {
            for (int z = -20; z < 20; z++) {
                for (int y = 60; y < 70; y++) {
                    float value = EmberVeinReach.hash(at(x, y, z));
                    assertTrue(value >= 0.0f && value < 1.0f);
                    counts[(int) (value * buckets)]++;
                    samples++;
                }
            }
        }
        for (int count : counts) {
            // 16 000 samples: each tenth should hold about 1 600.
            assertTrue(Math.abs(count - samples / buckets) < samples / buckets * 0.1, "uneven hash: " + java.util.Arrays.toString(counts));
        }
        // Neighbours differ, so veins don't come in stripes.
        assertTrue(EmberVeinReach.hash(at(0, 64, 0)) != EmberVeinReach.hash(at(1, 64, 0)));
    }

    @Test
    void stepsCountAlongAChainAndStopAtTheLastGlowingStep() {
        LongOpenHashSet flammable = new LongOpenHashSet();
        for (int x = 1; x <= 10; x++) {
            flammable.add(at(x, 64, 0));
        }
        LongArrayList fires = LongArrayList.of(at(0, 64, 0));
        search(fires, flammable);
        for (int x = 1; x <= EmberVeinReach.MAX_STEPS; x++) {
            assertEquals(x, reached.get(at(x, 64, 0)));
        }
        assertFalse(reached.containsKey(at(EmberVeinReach.MAX_STEPS + 1, 64, 0)), "the search stops where the chance is 0");
        assertFalse(reached.containsKey(at(0, 64, 0)), "fires aren't veins");
    }

    @Test
    void onlyTouchingFlammableBlocksConduct() {
        LongOpenHashSet flammable = new LongOpenHashSet();
        flammable.add(at(1, 64, 0));
        // A gap of stone at x = 2, then more wood.
        flammable.add(at(3, 64, 0));
        // Touching the fire only at an edge, never through a face.
        flammable.add(at(1, 65, 1));
        search(LongArrayList.of(at(0, 64, 0)), flammable);
        assertEquals(1, reached.get(at(1, 64, 0)));
        assertFalse(reached.containsKey(at(3, 64, 0)));
        assertFalse(reached.containsKey(at(1, 65, 1)));
    }

    @Test
    void theNearestFireSetsTheSteps() {
        LongOpenHashSet flammable = new LongOpenHashSet();
        for (int x = 1; x <= 5; x++) {
            flammable.add(at(x, 64, 0));
        }
        search(LongArrayList.of(at(0, 64, 0), at(6, 64, 0)), flammable);
        assertEquals(1, reached.get(at(1, 64, 0)));
        assertEquals(2, reached.get(at(2, 64, 0)));
        assertEquals(3, reached.get(at(3, 64, 0)));
        assertEquals(2, reached.get(at(4, 64, 0)));
        assertEquals(1, reached.get(at(5, 64, 0)));
    }

    @Test
    void aForestStaysWithinTheCap() {
        LongOpenHashSet flammable = new LongOpenHashSet();
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                for (int y = 60; y < 70; y++) {
                    flammable.add(at(x, y, z));
                }
            }
        }
        LongArrayList fires = new LongArrayList();
        for (int x = -40; x <= 40; x += 4) {
            for (int z = -40; z <= 40; z += 4) {
                flammable.remove(at(x, 70, z));
                fires.add(at(x, 70, z));
            }
        }
        int count = search(fires, flammable);
        assertTrue(count <= EmberVeinReach.REACH_CAP);
        assertEquals(count, reached.size());
    }

    @Test
    void theSameWorldGivesTheSameVeins() {
        LongOpenHashSet flammable = new LongOpenHashSet();
        for (int x = -3; x <= 3; x++) {
            for (int y = 60; y <= 66; y++) {
                flammable.add(at(x, y, 0));
            }
        }
        flammable.remove(at(0, 63, 0));
        LongArrayList fires = LongArrayList.of(at(0, 63, 0));
        search(fires, flammable);
        LongOpenHashSet first = new LongOpenHashSet();
        reached.forEach((position, steps) -> {
            if (EmberVeinReach.shows(position, steps)) {
                first.add((long) position);
            }
        });
        search(fires, flammable);
        LongOpenHashSet second = new LongOpenHashSet();
        reached.forEach((position, steps) -> {
            if (EmberVeinReach.shows(position, steps)) {
                second.add((long) position);
            }
        });
        assertEquals(first, second);
        assertFalse(first.isEmpty());
    }
}
