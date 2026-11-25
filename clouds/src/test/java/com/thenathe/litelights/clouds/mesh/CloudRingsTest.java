package com.thenathe.litelights.clouds.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The rings: every point in reach covered by exactly one chunk, cube sizes growing outward, far rings sampled in turns. */
class CloudRingsTest {
    @Test
    void everyPointInReachIsCoveredOnce() {
        double cameraX = 1_234.5;
        double cameraZ = -987.25;
        int reach = 8_192;
        List<CloudRings.Chunk> chunks = CloudRings.select(cameraX, cameraZ, reach);
        java.util.Random random = new java.util.Random(3);
        for (int i = 0; i < 20_000; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * reach;
            double x = cameraX + Math.cos(angle) * distance;
            double z = cameraZ + Math.sin(angle) * distance;
            int covering = 0;
            for (CloudRings.Chunk chunk : chunks) {
                double blocks = chunk.blocks();
                if (x >= chunk.x() * blocks && x < (chunk.x() + 1) * blocks && z >= chunk.z() * blocks && z < (chunk.z() + 1) * blocks) {
                    covering++;
                    // A chunk's cubes are no wider than the ring its nearest point lies in allows.
                    int level = chunk.level();
                    assertTrue(level == 0 || chunk.distance() >= CloudRings.ringEdge(level - 1), "a chunk of wide cubes inside a finer ring");
                }
            }
            assertEquals(1, covering, "a point " + distance + " blocks out is covered once");
        }
    }

    @Test
    void ringsGrowOutwardAndComeNearestFirst() {
        List<CloudRings.Chunk> chunks = CloudRings.select(0.0, 0.0, 8_192);
        Set<Integer> levels = new HashSet<>();
        double last = -1.0;
        for (CloudRings.Chunk chunk : chunks) {
            levels.add(chunk.level());
            assertTrue(chunk.distance() >= last, "nearest first");
            last = chunk.distance();
            if (chunk.distance() < CloudRings.NEAR_RING - 1.0) {
                assertEquals(0, chunk.level(), "8-block cubes near the camera");
            }
        }
        assertEquals(Set.of(0, 1, 2, 3), levels);
        // A short reach is the near ring alone, as stage 2 drew it.
        for (CloudRings.Chunk chunk : CloudRings.select(0.0, 0.0, 1_024)) {
            assertEquals(0, chunk.level());
        }
    }

    @Test
    void farRingsAreSampledInTurns() {
        List<CloudRings.Chunk> chunks = CloudRings.select(0.0, 0.0, 8_192);
        for (int level = 0; level < CloudRings.RINGS; level++) {
            int inRing = 0;
            int dueNow = 0;
            int[] timesDue = new int[chunks.size()];
            for (int build = 0; build < 8; build++) {
                for (int i = 0; i < chunks.size(); i++) {
                    CloudRings.Chunk chunk = chunks.get(i);
                    if (chunk.level() != level) {
                        continue;
                    }
                    if (CloudRings.due(chunk, build)) {
                        timesDue[i]++;
                        dueNow += build == 0 ? 1 : 0;
                    }
                    inRing += build == 0 ? 1 : 0;
                }
            }
            int every = 1 << level;
            for (int i = 0; i < chunks.size(); i++) {
                if (chunks.get(i).level() == level) {
                    assertEquals(8 / every, timesDue[i], "each chunk of ring " + level + " is sampled every " + every + " builds");
                }
            }
            assertTrue(level == 0 || dueNow < inRing, "a far ring isn't sampled all at once");
        }
    }
}
