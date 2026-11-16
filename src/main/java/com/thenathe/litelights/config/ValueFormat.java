package com.thenathe.litelights.config;

/**
 * How a number option reads on screen. The stored value is always the plain number; only the
 * text changes (1.5 with {@link #PERCENT} reads "150%").
 */
public enum ValueFormat {
    /** The number as it is: "3". */
    PLAIN,
    /** A fraction shown as a percentage: 0.8 reads "80%". */
    PERCENT,
    /** A fraction around zero shown with its sign: -0.25 reads "-25%", 0 reads "0%". */
    SIGNED_PERCENT,
    /** Degrees with a sign: "+12°". */
    DEGREES,
    /** Exposure stops with a sign: "+0.5 EV". */
    STOPS,
    /** A distance: "256 blocks". */
    BLOCKS,
    /** A square of blocks: 4 reads "4×4 blocks". */
    BLOCK_SQUARE
}
