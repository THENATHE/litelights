package com.thenathe.litelights.api;

import org.joml.Matrix4fc;

/**
 * The camera the level is drawn from this frame (docs/ARCHITECTURE.md, "The LiteFrame block" and
 * "Anti-aliasing seam"). Positions in LiteLights' shaders are camera-relative: world position minus
 * this camera's position. Valid from the frame update until the level is finished; render thread only.
 */
public interface CameraView {
    double x();

    double y();

    double z();

    /**
     * Camera-relative position to clip space exactly as the level's own geometry is drawn: view
     * bobbing included, and TAA's sub-pixel jitter when it is on. Geometry drawn into the main
     * depth with this matrix meets the level's depth without a seam.
     */
    Matrix4fc drawViewProjection();

    /** The same without the jitter (LiteViewProjection): for culling, reprojection and distances. */
    Matrix4fc viewProjection();
}
