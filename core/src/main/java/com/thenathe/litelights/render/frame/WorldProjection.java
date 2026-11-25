package com.thenathe.litelights.render.frame;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The one place the world's projection matrix passes through on its way to the GPU (TAA's seam).
 * {@link #adjust} receives vanilla's level projection, with view bobbing and nausea already
 * applied, just before it is uploaded. It returns the matrix the level is drawn with: the same
 * matrix, or with TAA on, that matrix with the frame's sub-pixel jitter added.
 *
 * <p>Only the level projection comes through here. The hand and HUD (3D HUD projection) and the GUI
 * (orthographic) use their own matrices and are never jittered.
 *
 * <p>The unjittered matrix is kept for the LiteFrame block, so effects that reproject between frames
 * (and the TAA resolve itself) work from stable matrices.
 */
public final class WorldProjection {
    private final Matrix4f unjittered = new Matrix4f();
    private final Matrix4f drawn = new Matrix4f();
    private final Matrix4f jitter = new Matrix4f();
    private boolean jitterEnabled;

    public Matrix4f adjust(Matrix4f projection) {
        unjittered.set(projection);
        if (!jitterEnabled) {
            drawn.set(projection);
            return projection;
        }
        Matrix4f jittered = jitter.mul(projection, new Matrix4f());
        drawn.set(jittered);
        return jittered;
    }

    /** The projection the level is drawn with this frame: the jittered one while TAA jitters. */
    public Matrix4fc drawn() {
        return drawn;
    }

    /** The projection before any jitter: what LiteFrame's view-projection matrices are built from. */
    public Matrix4fc unjittered() {
        return unjittered;
    }

    /** Offsets the projection by (x, y) pixels from the next frame on (TAA's jitter); (0, 0) turns it off. */
    public void setJitter(float pixelX, float pixelY, int screenWidth, int screenHeight) {
        jitterEnabled = pixelX != 0.0f || pixelY != 0.0f;
        jitter.translation(2.0f * pixelX / screenWidth, 2.0f * pixelY / screenHeight, 0.0f);
    }
}
