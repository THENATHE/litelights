package com.thenathe.litelights.render.shadow;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Random;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NearestChangeTest {
    /** What nearestChangeWithin did before it learnt to skip far sections: measure every one. */
    private static double measuringAll(LongArrayList changed, double centerX, double centerZ, double radius, double fromX, double fromZ) {
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < changed.size(); i++) {
            long key = changed.getLong(i);
            double x = SectionPos.sectionToBlockCoord(SectionPos.x(key)) + 8.0;
            double z = SectionPos.sectionToBlockCoord(SectionPos.z(key)) + 8.0;
            if (Math.abs(x - centerX) <= radius + 8.0 && Math.abs(z - centerZ) <= radius + 8.0) {
                nearest = Math.min(nearest, Math.hypot(x - fromX, z - fromZ));
            }
        }
        return nearest;
    }

    @Test
    void sameAsMeasuringEverySection() {
        Random random = new Random(95);
        for (int round = 0; round < 2_000; round++) {
            double cameraX = (random.nextDouble() - 0.5) * 60_000_000.0 * (round % 2 == 0 ? 1.0 : 1.0e-4);
            double cameraZ = (random.nextDouble() - 0.5) * 60_000_000.0 * (round % 2 == 0 ? 1.0 : 1.0e-4);
            int cameraSectionX = SectionPos.blockToSectionCoord(cameraX);
            int cameraSectionZ = SectionPos.blockToSectionCoord(cameraZ);
            LongArrayList changed = new LongArrayList();
            int count = random.nextInt(3_000);
            for (int i = 0; i < count; i++) {
                // Clusters and exact ties (the same section twice, mirrored sections) included.
                int dx = random.nextInt(65) - 32;
                int dz = random.nextInt(65) - 32;
                changed.add(SectionPos.asLong(cameraSectionX + dx, random.nextInt(24) - 4, cameraSectionZ + dz));
                if (random.nextInt(8) == 0) {
                    changed.add(SectionPos.asLong(cameraSectionX - dx, 0, cameraSectionZ - dz));
                }
            }
            double radius = 32.0 + random.nextInt(480);
            double centerX = cameraX + random.nextGaussian() * 40.0;
            double centerZ = cameraZ + random.nextGaussian() * 40.0;
            assertEquals(measuringAll(changed, centerX, centerZ, radius, cameraX, cameraZ),
                ShadowCasterStore.nearestChangeWithin(changed, centerX, centerZ, radius, cameraX, cameraZ),
                0.0, "round " + round);
        }
    }

    @Test
    void nothingChangedIsInfinitelyFar() {
        assertEquals(Double.POSITIVE_INFINITY, ShadowCasterStore.nearestChangeWithin(new LongArrayList(), 0, 0, 256, 0, 0));
    }
}
