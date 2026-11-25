package com.thenathe.litelights.api;

/**
 * The LiteFlags feature bits (frame.glsl) a module reads or sets. Core's FrameFlags takes these
 * values from here, so the two can't drift apart.
 */
public final class FrameBits {
    /** Sun shadows are on and drawn this frame (core-render). */
    public static final int SHADOWS = 1;
    /** LiteLights' own far terrain is drawn this frame (the far terrain module). */
    public static final int FAR_TERRAIN = 1 << 3;

    private FrameBits() {
    }
}
