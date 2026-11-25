package com.thenathe.litelights.overlays.generation;

import java.lang.management.ManagementFactory;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Where one variety set's time and allocation go. Runs only with -Plitelights.overlays.probe=true. */
class GenerationCostProbe {
    @Test
    void probe() {
        Assumptions.assumeTrue(Boolean.getBoolean("litelights.overlays.probe"));
        for (int size : new int[] {16, 128, 256}) {
            int[] texture = new int[size * size];
            Random random = new Random(size);
            for (int i = 0; i < texture.length; i++) {
                texture[i] = 0xFF000000 | random.nextInt(0xFFFFFF);
            }
            VarietyLayout layout = new VarietyLayout(2, 2);
            for (int round = 0; round < 3; round++) {
                long[] shapeCost = measure(() -> {
                    EdgeShape shape = new EdgeShape(EdgeStyle.ORGANIC, size, 4, 0.6, false, 7);
                    for (int direction = 0; direction < 4; direction++) {
                        for (int a = 0; a < 2; a++) {
                            for (int b = 0; b < 2; b++) {
                                for (int wave = 0; wave < 2; wave++) {
                                    shape.band(direction, a, b, wave, 2);
                                }
                            }
                        }
                    }
                });
                EdgeShape shape = new EdgeShape(EdgeStyle.ORGANIC, size, 4, 0.6, false, 7);
                byte[] mask = shape.band(0, 0, 1, 1, 2);
                int[][] composed = new int[1][];
                long[] composeCost = measure(() -> {
                    for (int i = 0; i < 32; i++) {
                        composed[0] = TileComposer.compose(texture, size, mask, TileComposer.Mapping.FACE);
                    }
                });
                long[] pngCost = measure(() -> {
                    for (int i = 0; i < 32; i++) {
                        PngWriter.encode(composed[0], size, size);
                    }
                });
                System.out.printf("size %d round %d: 32 bands -> shape %d ms %d MiB, compose %d ms %d MiB, png %d ms %d MiB%n",
                    size, round, shapeCost[0], shapeCost[1], composeCost[0], composeCost[1], pngCost[0], pngCost[1]);
            }
        }
    }

    private static long[] measure(Runnable work) {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocated = threads.getCurrentThreadAllocatedBytes();
        long started = System.nanoTime();
        work.run();
        return new long[] {(System.nanoTime() - started) / 1_000_000,
            (threads.getCurrentThreadAllocatedBytes() - allocated) / (1024 * 1024)};
    }
}
