package com.thenathe.litelights.clouds.mesh;

import com.thenathe.litelights.clouds.field.CloudColumn;
import com.thenathe.litelights.clouds.field.CloudField;
import com.thenathe.litelights.clouds.field.VanillaWeatherSky;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A chunk's build split into sampling the field and meshing, per ring, in clear weather, rain and
 * thunder, on one thread; printed only, and only with {@code -Plitelights.clouds.probe=true}
 * (docs/CLOUDS.md, Cost).
 */
class ChunkCostProbe {
    @Test
    void sampleAndMeshPerRing() {
        Assumptions.assumeTrue(Boolean.getBoolean("litelights.clouds.probe"), "a probe, run only when asked for");
        for (float[] weather : new float[][] {{0.0f, 0.0f}, {1.0f, 0.0f}, {1.0f, 1.0f}}) {
            CloudField field = new CloudField(VanillaWeatherSky.sky(0x51CE5EEDL, 192.0f, 30_000.0, weather[0], weather[1], 0.0, 0.0, 9_000.0));
            for (int level = 0; level < CloudRings.RINGS; level++) {
                int cube = CloudRings.NEAR_CUBE << level;
                double chunkBlocks = CloudCells.SIZE * cube;
                int count = 16;
                double[] sample = new double[count];
                double[] mesh = new double[count];
                double[] shading = new double[count];
                int faces = 0;
                for (int round = 0; round < 2; round++) {
                    for (int i = 0; i < count; i++) {
                        int chunkX = i % 4 - 2 + (level == 0 ? 0 : 2 * level);
                        int chunkZ = i / 4 - 2;
                        double minX = chunkX * chunkBlocks;
                        double minZ = chunkZ * chunkBlocks;
                        long started = System.nanoTime();
                        CloudField.Area area = field.area(30_000.0, minX - 2 * cube, minZ - 2 * cube, minX + chunkBlocks + 2 * cube,
                            minZ + chunkBlocks + 2 * cube);
                        CloudCells cells = CloudCells.sample(area, chunkX, chunkZ, 192.0f, cube, new CloudColumn());
                        long sampled = System.nanoTime();
                        if (!cells.isEmpty()) {
                            CloudShading.of(cells, cells.lowestCell(), cells.highestCell());
                        }
                        shading[i] = (System.nanoTime() - sampled) / 1.0e6;
                        sampled = System.nanoTime();
                        FaceList list = new FaceList();
                        CloudMesher.mesh(cells, cells, 0, list);
                        long meshed = System.nanoTime();
                        sample[i] = (sampled - started) / 1.0e6;
                        mesh[i] = (meshed - sampled) / 1.0e6;
                        if (round == 1) {
                            faces += list.size();
                        }
                    }
                }
                Arrays.sort(sample);
                Arrays.sort(mesh);
                Arrays.sort(shading);
                System.out.println(String.format(Locale.ROOT, "  shading prep median %.2f ms", shading[count / 2]));
                System.out.println(String.format(Locale.ROOT, "rain %.0f thunder %.0f, %d-block cubes: sample median %.2f ms (slowest %.2f), mesh median %.2f ms (slowest %.2f), %d faces a chunk",
                    weather[0], weather[1], cube, sample[count / 2], sample[count - 1], mesh[count / 2], mesh[count - 1], faces / count));
            }
        }
    }
}
