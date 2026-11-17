package com.thenathe.litelights.config;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Calculate distance: Reach meets the far terrain at the render distance, within Reach's range. */
class FarTerrainReachTest {
    @TempDir
    static Path folder;

    @BeforeAll
    static void loadOptions() {
        if (!LiteLightsConfig.loaded()) {
            LiteLightsConfig.useFile(folder.resolve("litelights.json"));
        }
        LiteLightsConfig.init();
    }

    @Test
    void reachIsTheRenderDistanceInBlocks() {
        assertEquals(192, FarTerrainReach.forRenderDistance(12));
        assertEquals(128, FarTerrainReach.forRenderDistance(8));
        assertEquals(256, FarTerrainReach.forRenderDistance(16));
        assertEquals(512, FarTerrainReach.forRenderDistance(32));
    }

    @Test
    void reachStaysInItsRange() {
        // Past 32 chunks the far terrain begins beyond the longest Reach; below 4, inside the shortest.
        assertEquals(512, FarTerrainReach.forRenderDistance(33));
        assertEquals(512, FarTerrainReach.forRenderDistance(64));
        assertEquals(64, FarTerrainReach.forRenderDistance(3));
        assertEquals(64, FarTerrainReach.forRenderDistance(2));
        for (int chunks = 2; chunks <= 64; chunks++) {
            int reach = FarTerrainReach.forRenderDistance(chunks);
            assertEquals(0, reach % LiteLightsOptions.SHADOW_REACH.step(), "on Reach's step at " + chunks);
            LiteLightsOptions.SHADOW_REACH.set(reach);
            assertEquals(reach, LiteLightsOptions.SHADOW_REACH.value(), "a value Reach takes as it is at " + chunks);
        }
        LiteLightsOptions.SHADOW_REACH.reset();
    }
}
