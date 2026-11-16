package com.thenathe.litelights.render.feature.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidStripLayoutTest {
    // Big Water's vanilla-style textures: water 64 px still (128 frames), 128 px flowing (64 frames);
    // lava 64 px still (64 frames, interpolated), 128 px flowing (128 frames, interpolated).
    private static FluidStripLayout bigWater() {
        return FluidStripLayout.of(64, 128,
            new FluidStripLayout.Source(128, 2, false), new FluidStripLayout.Source(64, 1, false),
            new FluidStripLayout.Source(64, 1, true), new FluidStripLayout.Source(128, 2, true));
    }

    @Test
    void columnsSitSideBySide() {
        FluidStripLayout layout = bigWater();
        assertEquals(384, layout.width());
        assertEquals(0, layout.waterStillColumn());
        assertEquals(64, layout.waterFlowColumn());
        assertEquals(192, layout.lavaStillColumn());
        assertEquals(256, layout.lavaFlowColumn());
    }

    @Test
    void tooLongAnimationsKeepEverySecondFrameAndShowItTwiceAsLong() {
        FluidStripLayout layout = bigWater();
        assertEquals(FluidStripLayout.MAX_HEIGHT, layout.height());
        assertEquals(64, layout.lavaFlow().frames());
        assertEquals(2, layout.lavaFlow().sourceStep());
        assertEquals(4.0f, layout.lavaFlow().ticksPerFrame());
        assertEquals(128, layout.waterStill().frames(), "water still fits as it is");
        assertEquals(1, layout.waterStill().sourceStep());
    }

    @Test
    void shippedLavaFlowShowsTheSameFramesAsBigWatersFull() {
        // LiteLights ships lava_flow.png with only the even frames (64) at frametime 4: the same
        // frames, each shown as long, as the full 128-frame file thinned by the layout.
        FluidStripLayout shipped = FluidStripLayout.of(64, 128,
            new FluidStripLayout.Source(128, 2, false), new FluidStripLayout.Source(64, 1, false),
            new FluidStripLayout.Source(64, 1, true), new FluidStripLayout.Source(64, 4, true));
        FluidStripLayout.Animation full = bigWater().lavaFlow();
        assertEquals(full.frames(), shipped.lavaFlow().frames());
        assertEquals(full.ticksPerFrame(), shipped.lavaFlow().ticksPerFrame());
        assertEquals(full.interpolate(), shipped.lavaFlow().interpolate());
        assertEquals(1, shipped.lavaFlow().sourceStep());
        assertEquals(bigWater().height(), shipped.height());
    }

    @Test
    void mipLevelsStopAtOneTexelPerStillFrame() {
        FluidStripLayout layout = bigWater();
        assertEquals(7, layout.mipLevels());
        assertEquals(1, layout.stillSize() >> (layout.mipLevels() - 1));
        assertTrue(layout.height() % (1 << (layout.mipLevels() - 1)) == 0);
    }

    @Test
    void flowingTilesCoverTwiceTheBlocksOfStillOnes() {
        assertEquals(8, bigWater().flowTileBlocks(4));
        assertEquals(14, bigWater().flowTileBlocks(7));
    }

    @Test
    void framesStepWithGameTimeAndWrap() {
        FluidStripLayout.Animation still = bigWater().waterStill();
        assertEquals(0.0f, still.frameAt(0.0));
        assertEquals(0.0f, still.frameAt(1.9), "two ticks per frame, not interpolated");
        assertEquals(1.0f, still.frameAt(2.0));
        assertEquals(0.0f, still.frameAt(256.0), "128 frames of two ticks loop after 256 ticks");
    }

    @Test
    void interpolatedFramesKeepTheirFraction() {
        FluidStripLayout.Animation lava = bigWater().lavaStill();
        assertEquals(1.5f, lava.frameAt(1.5), 1.0e-6);
    }

    @Test
    void framesMustBePowersOfTwo() {
        FluidStripLayout.Source one = new FluidStripLayout.Source(1, 1, false);
        assertThrows(IllegalArgumentException.class, () -> FluidStripLayout.of(48, 96, one, one, one, one));
        assertThrows(IllegalArgumentException.class, () -> FluidStripLayout.of(64, 32, one, one, one, one));
    }
}
