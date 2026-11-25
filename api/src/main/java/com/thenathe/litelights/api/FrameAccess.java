package com.thenathe.litelights.api;

import com.mojang.renderpearl.api.commands.RenderPass;

/** The frame core is drawing: the LiteFrame block's values (docs/ARCHITECTURE.md, "The LiteFrame block"). */
public interface FrameAccess {
    /** This frame's feature bits (LiteFlags.x, {@link FrameBits}), as far as they are set when asked. */
    int featureFlags();

    /**
     * Sets feature bits of a module's own ({@link FrameBits}) in this frame's block. Only during
     * {@link ModuleEvents#FRAME_UPDATE}, before the block is uploaded; the per-dimension switches
     * run after it and may still clear them.
     */
    void addFeatureFlags(int bits);

    /** Binds LiteLights' world uniforms (the LiteFrame block, shadow maps, materials) on a pass of the caller's own. */
    void bindWorldUniforms(RenderPass pass);

    /** The camera and matrices the level is drawn with this frame. */
    CameraView camera();

    /** How much daylight there is this frame, from the sun's height: 0 at night to 1 by day (LiteSunColor.a). */
    float daylight();
}
