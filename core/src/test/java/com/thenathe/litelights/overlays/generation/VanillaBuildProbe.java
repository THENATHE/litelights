package com.thenathe.litelights.overlays.generation;

import com.thenathe.litelights.overlays.config.OverlayConfig;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Times a whole build of the default hierarchy from vanilla's own textures, at 16x and blown up to
 * 128x, the way the game builds it (all worker threads). Runs only with
 * -Plitelights.overlays.probe=true; the numbers go to standard output. Round 0 includes the JIT
 * warming up; round 1 is a cold build of changed textures; round 2 reuses round 1's tiles, like a
 * reload that changed nothing. Each is run without LambdaBetterGrass and as if it were installed
 * (its vanilla blocks' sides deferred to it).
 */
class VanillaBuildProbe {
    @Test
    void probe() {
        Assumptions.assumeTrue(Boolean.getBoolean("litelights.overlays.probe"));
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (boolean betterGrass : new boolean[] {false, true}) {
            for (int scale : new int[] {1, 8}) {
                for (OverlayFormat format : OverlayFormat.values()) {
                    for (int round = 0; round < 3; round++) {
                        long allocated = threads.getTotalThreadAllocatedBytes();
                        GenerationReport report = VanillaSources.build(new OverlayConfig(), format, scale, Math.min(round, 1),
                            betterGrass).report();
                        String kind = round == 0 ? "first, with JIT warm-up" : round == 1 ? "cold" : "reusing round 1";
                        System.out.printf("vanilla x%d %s%s round %d (%s): %s %s; %d MiB allocated%n", scale, format,
                            betterGrass ? " with LambdaBetterGrass" : "", round, kind, report.summary(), report.skipped(),
                            (threads.getTotalThreadAllocatedBytes() - allocated) >> 20);
                    }
                }
            }
        }
    }
}
