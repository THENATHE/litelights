package com.thenathe.litelights.clouds.field;

/** What falls from a formation. */
public enum Precipitation {
    NONE,
    /** Rain or snow, whichever the ground's biome gives: vanilla's weather, which knows no more. */
    BY_BIOME,
    RAIN,
    SNOW,
    /** A storm over land where nothing can fall: dark sky, no rain (drawn as a sandstorm). */
    DRY
}
