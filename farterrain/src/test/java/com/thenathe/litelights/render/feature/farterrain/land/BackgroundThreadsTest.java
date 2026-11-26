package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.thenathe.litelights.render.feature.farterrain.land.FarLandSettings.Threads;
import org.junit.jupiter.api.Test;

/** The Background threads row's counts for a machine (FarLandSettings.meshThreads). */
class BackgroundThreadsTest {
    @Test
    void autoIsHalfTheProcessorLessTwoOneToEight() {
        assertEquals(1, FarLandSettings.meshThreads(Threads.AUTO, 2));
        assertEquals(1, FarLandSettings.meshThreads(Threads.AUTO, 4));
        assertEquals(2, FarLandSettings.meshThreads(Threads.AUTO, 8));
        assertEquals(6, FarLandSettings.meshThreads(Threads.AUTO, 16));
        assertEquals(8, FarLandSettings.meshThreads(Threads.AUTO, 64));
    }

    @Test
    void allButTwoAndCountsNeverPassTheProcessor() {
        assertEquals(14, FarLandSettings.meshThreads(Threads.ALL_BUT_TWO, 16));
        assertEquals(1, FarLandSettings.meshThreads(Threads.ALL_BUT_TWO, 2));
        assertEquals(8, FarLandSettings.meshThreads(Threads.EIGHT, 16));
        assertEquals(4, FarLandSettings.meshThreads(Threads.EIGHT, 4));
        assertEquals(1, FarLandSettings.meshThreads(Threads.ONE, 16));
    }
}
