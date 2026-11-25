package com.thenathe.litelights.clouds.mesh;

import org.jspecify.annotations.Nullable;

/**
 * The cloud vertex shader's sizing of a changing cube ({@code shaders/clouds/dynamic.vsh}), in
 * Java for the tests: keep the two in step.
 */
final class CubeGrowth {
    static final float GROW_EPSILON = 1.0f / 128.0f;

    private CubeGrowth() {
    }

    /**
     * The cube's box at a point of the change, as {low x, y, z, high x, y, z} in the unit cube, or
     * null when it isn't drawn at all.
     */
    static float @Nullable [] box(int look, float progress) {
        float[] low = {0.0f, 0.0f, 0.0f};
        float[] high = {1.0f, 1.0f, 1.0f};
        int state = FaceFormat.state(look);
        if (state != FaceFormat.STABLE) {
            float start = FaceFormat.windowStart(look);
            float end = FaceFormat.windowEnd(look);
            boolean appearing = state == FaceFormat.APPEARING;
            if (appearing ? progress < start : progress > end) {
                return null;
            }
            float done = end > start ? Math.clamp((progress - start) / (end - start), 0.0f, 1.0f) : 1.0f;
            float fill = Math.max(appearing ? done : 1.0f - done, GROW_EPSILON);
            float across = FaceFormat.widens(look) ? Math.max(appearing ? progress : 1.0f - progress, GROW_EPSILON) : 1.0f;
            int from = FaceFormat.growFrom(look);
            if (from < 0) {
                low = new float[] {0.5f - 0.5f * across, 0.5f - 0.5f * fill, 0.5f - 0.5f * across};
                high = new float[] {0.5f + 0.5f * across, 0.5f + 0.5f * fill, 0.5f + 0.5f * across};
            } else {
                int axis = from < 2 ? 1 : (from < 4 ? 2 : 0);
                if (across < 1.0f) {
                    low = new float[] {0.5f - 0.5f * across, 0.5f - 0.5f * across, 0.5f - 0.5f * across};
                    high = new float[] {0.5f + 0.5f * across, 0.5f + 0.5f * across, 0.5f + 0.5f * across};
                }
                low[axis] = (from & 1) == 0 ? 0.0f : 1.0f - fill;
                high[axis] = (from & 1) == 0 ? fill : 1.0f;
            }
        }
        return new float[] {low[0], low[1], low[2], high[0], high[1], high[2]};
    }

    /** The box's share of the whole cube, 0 when it isn't drawn. */
    static float volume(int look, float progress) {
        float[] box = box(look, progress);
        if (box == null) {
            return 0.0f;
        }
        return (box[3] - box[0]) * (box[4] - box[1]) * (box[5] - box[2]);
    }
}
