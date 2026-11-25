package com.thenathe.litelights.render.feature.lightpost;

/**
 * TAA's sub-pixel jitter: where inside its pixel each frame samples the level, in pixels, from
 * -0.5 to 0.5 on each axis. The first eight points of the Halton (2, 3) sequence spread over the
 * pixel evenly enough that eight frames cover it; the sequence is shifted so its points average
 * to the pixel's centre, or the settled picture would sit a little off the unjittered one (by
 * 0.055 pixels to the left for the raw sequence).
 */
final class TaaJitter {
    static final int STEPS = 8;

    private static final float[] X = new float[STEPS];
    private static final float[] Y = new float[STEPS];

    static {
        float sumX = 0.0f;
        float sumY = 0.0f;
        for (int i = 0; i < STEPS; i++) {
            X[i] = halton(i + 1, 2) - 0.5f;
            Y[i] = halton(i + 1, 3) - 0.5f;
            sumX += X[i];
            sumY += Y[i];
        }
        for (int i = 0; i < STEPS; i++) {
            X[i] -= sumX / STEPS;
            Y[i] -= sumY / STEPS;
        }
    }

    private TaaJitter() {
    }

    /** The jitter of a frame, in pixels; frames count up from any number. */
    static float x(int frame) {
        return X[Math.floorMod(frame, STEPS)];
    }

    static float y(int frame) {
        return Y[Math.floorMod(frame, STEPS)];
    }

    /** The index-th point of the van der Corput sequence in this base (0..1). */
    static float halton(int index, int base) {
        float result = 0.0f;
        float fraction = 1.0f / base;
        while (index > 0) {
            result += fraction * (index % base);
            index /= base;
            fraction /= base;
        }
        return result;
    }
}
