package com.thenathe.litelights.render.post;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.thenathe.litelights.render.frame.FrameClock;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * GPU time of a few sections of a frame, from timestamp queries. A ring of frames keeps each
 * frame's queries until the GPU has finished them: a frame's results are read when its slot comes
 * round again, a few frames later. Render thread only; the query pool is made on first use.
 */
public final class GpuTimer {
    /** Receives one finished measurement, with the number of the frame it was taken in ({@link FrameClock#frameNumber}). */
    @FunctionalInterface
    public interface Results {
        void add(int section, double millis, long frame);
    }

    private static final int FRAMES = 4;

    private final int sections;
    private final boolean[][] written;
    private final long[] slotFrames = new long[FRAMES];
    private @Nullable GpuQueryPool pool;
    private int ring;

    public GpuTimer(int sections) {
        this.sections = sections;
        this.written = new boolean[FRAMES][sections];
    }

    /** Moves on to the next frame's slot, handing over what the GPU measured there last time round. */
    public void nextFrame(Results results) {
        if (pool == null) {
            pool = RenderSystem.getDevice().createTimestampQueryPool(FRAMES * sections * 2);
        }
        ring = (ring + 1) % FRAMES;
        double period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
        for (int section = 0; section < sections; section++) {
            if (!written[ring][section]) {
                continue;
            }
            written[ring][section] = false;
            OptionalLong[] values = pool.getValues(index(section), 2);
            if (values[0].isPresent() && values[1].isPresent()) {
                results.add(section, (values[1].getAsLong() - values[0].getAsLong()) * period / 1.0e6, slotFrames[ring]);
            }
        }
        slotFrames[ring] = FrameClock.frameNumber();
    }

    public void begin(int section) {
        if (pool != null) {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, index(section));
        }
    }

    public void end(int section) {
        if (pool != null) {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, index(section) + 1);
            written[ring][section] = true;
        }
    }

    private int index(int section) {
        return (ring * sections + section) * 2;
    }
}
