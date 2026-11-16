package com.thenathe.litelights.render.feature.water;

/**
 * How the large fluid textures are packed into one GPU texture: four columns side by side (water
 * still, water flow, lava still, lava flow), each a stack of square animation frames. Still frames
 * are {@code stillSize} texels across, flowing frames {@code flowSize}. Animations too long for
 * {@link #MAX_HEIGHT} keep every second (or third...) frame and show each one longer.
 *
 * <p>Pure arithmetic, unit-tested; {@link LargeFluidTextures} does the loading and uploading.
 */
public record FluidStripLayout(int stillSize, int flowSize, Animation waterStill, Animation waterFlow,
                               Animation lavaStill, Animation lavaFlow, int height) {
    /** Tall enough for every vanilla-style animation, and well inside every GPU's texture limit. */
    public static final int MAX_HEIGHT = 8192;

    /**
     * One animation as stored: {@code frames} frames, each shown {@code ticksPerFrame} game ticks,
     * blended into the next when {@code interpolate}. {@code sourceStep} is how many source frames
     * each stored frame stands for.
     */
    public record Animation(int frames, int sourceStep, float ticksPerFrame, boolean interpolate) {
        /** The frame to show at a game time in ticks; the fraction is the blend toward the next frame. */
        public float frameAt(double ticks) {
            double frame = (ticks / ticksPerFrame) % frames;
            if (frame < 0) {
                frame += frames;
            }
            return interpolate ? (float) frame : (float) Math.floor(frame);
        }
    }

    /** The source frames of one animation, as read from its PNG and .mcmeta. */
    public record Source(int frames, int ticksPerFrame, boolean interpolate) {
    }

    public static FluidStripLayout of(int stillSize, int flowSize, Source waterStill, Source waterFlow, Source lavaStill, Source lavaFlow) {
        if (Integer.bitCount(stillSize) != 1 || Integer.bitCount(flowSize) != 1 || flowSize < stillSize) {
            throw new IllegalArgumentException("Frame sizes must be powers of two, flowing at least as large as still: "
                + stillSize + ", " + flowSize);
        }
        Animation ws = fit(waterStill, stillSize);
        Animation wf = fit(waterFlow, flowSize);
        Animation ls = fit(lavaStill, stillSize);
        Animation lf = fit(lavaFlow, flowSize);
        int height = Math.max(Math.max(ws.frames * stillSize, ls.frames * stillSize), Math.max(wf.frames * flowSize, lf.frames * flowSize));
        return new FluidStripLayout(stillSize, flowSize, ws, wf, ls, lf, height);
    }

    private static Animation fit(Source source, int frameSize) {
        int framesThatFit = MAX_HEIGHT / frameSize;
        int step = Math.max(1, (source.frames + framesThatFit - 1) / framesThatFit);
        int frames = Math.max(1, source.frames / step);
        return new Animation(frames, step, (float) source.ticksPerFrame * step, source.interpolate);
    }

    public int width() {
        return 2 * stillSize + 2 * flowSize;
    }

    public int waterStillColumn() {
        return 0;
    }

    public int waterFlowColumn() {
        return stillSize;
    }

    public int lavaStillColumn() {
        return stillSize + flowSize;
    }

    public int lavaFlowColumn() {
        return 2 * stillSize + flowSize;
    }

    /** Mip levels down to one texel per still frame; every frame edge stays on a texel edge. */
    public int mipLevels() {
        return Integer.numberOfTrailingZeros(stillSize) + 1;
    }

    /** How many blocks a flowing tile covers for a still tile of {@code stillTileBlocks}. */
    public int flowTileBlocks(int stillTileBlocks) {
        return stillTileBlocks * flowSize / stillSize;
    }
}
