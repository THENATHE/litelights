package com.thenathe.litelights.render.timing;

/**
 * Which side of an interleaved A/B timing run a frame belongs to. The run starts on side A and
 * flips every {@code blockFrames} frames. The first {@code settleFrames} frames after each flip are
 * not measured: the flip itself can cost something (a settings snapshot rebuilt, a buffer
 * reallocated), and a frame's time is still shaped by the GPU work of the frame or two before it.
 *
 * <p>Pure arithmetic on frame numbers, so late GPU results can be put on the right side from the
 * number of the frame they were recorded in.
 */
public final class AbSchedule {
    public enum Side { A, B }

    private final int blockFrames;
    private final int settleFrames;
    private final long firstFrame;

    public AbSchedule(int blockFrames, int settleFrames, long firstFrame) {
        if (blockFrames < 1) {
            throw new IllegalArgumentException("a side needs at least one frame, not " + blockFrames);
        }
        if (settleFrames < 0 || settleFrames >= blockFrames) {
            throw new IllegalArgumentException("settling frames must leave a frame to measure: " + settleFrames + " of " + blockFrames);
        }
        this.blockFrames = blockFrames;
        this.settleFrames = settleFrames;
        this.firstFrame = firstFrame;
    }

    public int blockFrames() {
        return blockFrames;
    }

    public int settleFrames() {
        return settleFrames;
    }

    public long firstFrame() {
        return firstFrame;
    }

    /** Whether the frame is part of the run at all. */
    public boolean covers(long frame) {
        return frame >= firstFrame;
    }

    /** The side the frame is drawn on; frames before the run count as side A. */
    public Side sideOf(long frame) {
        return block(frame) % 2 == 0 ? Side.A : Side.B;
    }

    /** The block the frame falls in: blocks 0, 2, 4 are side A, 1, 3, 5 side B. */
    public long block(long frame) {
        return covers(frame) ? (frame - firstFrame) / blockFrames : 0L;
    }

    /** The side changes at the start of this frame (the run's first frame included). */
    public boolean flipsAt(long frame) {
        return covers(frame) && (frame - firstFrame) % blockFrames == 0;
    }

    /** Whether the frame's times count: inside the run and past its block's settling frames. */
    public boolean measured(long frame) {
        return covers(frame) && (frame - firstFrame) % blockFrames >= settleFrames;
    }
}
