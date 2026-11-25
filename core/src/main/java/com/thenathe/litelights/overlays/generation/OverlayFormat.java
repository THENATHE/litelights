package com.thenathe.litelights.overlays.generation;

/** The file format a build writes, both drawn by Continuity. */
public enum OverlayFormat {
    /** The module's own Continuity method: edge variety and spilling down steps. */
    VARIETY,
    /** Continuity's own {@code method=overlay}: what exports use, and the fallback if the variety method can't run. */
    PLAIN
}
