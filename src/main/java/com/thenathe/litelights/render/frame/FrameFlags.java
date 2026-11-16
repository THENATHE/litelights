package com.thenathe.litelights.render.frame;

/** Bit values for LiteFlags in frame.glsl. Keep the two lists in step. */
public final class FrameFlags {
    // In bit order, with their owner; free: 3 and 10 to 13.
    public static final int FEATURE_SHADOWS = 1;                // core-render
    public static final int FEATURE_ENHANCED_LIGHT = 2;         // light-post (set by core-render)
    public static final int FEATURE_MOB_SHADOWS = 4;            // core-render
    public static final int FEATURE_CAMERA_UNDERWATER = 16;     // core-render
    public static final int FEATURE_MATERIALS = 32;             // core-render
    public static final int FEATURE_SUN_MAP = 64;               // core-render
    public static final int FEATURE_MOB_MAP = 128;              // core-render
    public static final int FEATURE_SUN_MAP_FADING = 256;       // core-render
    public static final int FEATURE_ENHANCED_SHADOWS = 512;     // core-render
    public static final int FEATURE_MARK_NEAR = 1 << 10;        // far-terrain
    public static final int FEATURE_STORM_FOG = 1 << 14;        // sky-fog
    public static final int FEATURE_DARK_DISC = 1 << 15;        // sky-fog
    public static final int FEATURE_SHOOTING_STARS = 1 << 16;   // sky-fog
    public static final int FEATURE_REALISTIC_SKY = 1 << 17;    // sky-fog
    public static final int FEATURE_LITE_CLOUDS = 1 << 18;      // sky-fog
    public static final int FEATURE_LITE_SKY = 1 << 19;         // sky-fog
    /** Debug view, from -Dlitelights.debug=shadows: surfaces show the shadow lookup. */
    public static final int FEATURE_DEBUG_SHADOWS = 1 << 20;    // core-render
    public static final int FEATURE_LITE_FOG = 1 << 21;         // sky-fog
    public static final int FEATURE_HAZE = 1 << 22;             // sky-fog
    public static final int FEATURE_GROUND_MIST = 1 << 23;      // sky-fog
    public static final int FEATURE_BLOOM_MASK = 1 << 24;       // light-post
    public static final int FEATURE_NIGHT_TONE = 1 << 25;       // light-post
    public static final int FEATURE_TERRAIN_GLINT = 1 << 26;    // light-post
    public static final int FEATURE_UNDERWATER_GLINT = 1 << 27; // light-post
    public static final int FEATURE_RAY_LANDING = 1 << 28;      // light-post
    public static final int FEATURE_SKYLESS = 1 << 29;          // sky-fog
    public static final int FEATURE_END_TINT = 1 << 30;         // sky-fog

    public static final int LOCK_SHADOWS = 1;
    public static final int LOCK_CAUSTICS = 2;
    public static final int LOCK_WATER_SURFACE = 4;
    public static final int LOCK_GLINT = 8;
    public static final int LOCK_LIGHT_RAYS = 16;

    public static final int PASS_NEUTRAL = 0;
    public static final int PASS_WORLD = 1;
    /** The first-person hand: neutral, except that opaque draws write full alpha (near_mark.glsl). */
    public static final int PASS_HAND = 2;

    public static final int DIMENSION_OVERWORLD = 0;
    public static final int DIMENSION_NETHER = 1;
    public static final int DIMENSION_END = 2;
    public static final int DIMENSION_MODDED = 3;

    /*
     * Per-dimension switches (config.DimensionOptions.Aspect): the feature bits each switch clears
     * in a modded dimension where it is off. render.frame.DimensionGate applies them after every
     * FRAME_UPDATE listener has run. A stream adds its bits to the switch they belong to, and skips
     * its own Java-side work with DimensionGate.allows(aspect).
     */
    public static final int ASPECT_SKY_BITS = FEATURE_LITE_SKY | FEATURE_REALISTIC_SKY | FEATURE_SHOOTING_STARS | FEATURE_LITE_CLOUDS;
    public static final int ASPECT_SHADOWS_BITS = FEATURE_SHADOWS | FEATURE_ENHANCED_SHADOWS | FEATURE_MOB_SHADOWS
        | FEATURE_SUN_MAP | FEATURE_MOB_MAP | FEATURE_SUN_MAP_FADING;
    public static final int ASPECT_REFLECTIONS_BITS = 0;
    public static final int ASPECT_LIGHT_BITS = FEATURE_ENHANCED_LIGHT | FEATURE_NIGHT_TONE | FEATURE_TERRAIN_GLINT
        | FEATURE_UNDERWATER_GLINT | FEATURE_RAY_LANDING;
    public static final int ASPECT_BLOOM_BITS = FEATURE_BLOOM_MASK;
    public static final int ASPECT_OUTLINES_BITS = 0;
    public static final int ASPECT_EFFECTS_BITS = FEATURE_LITE_FOG | FEATURE_HAZE | FEATURE_GROUND_MIST | FEATURE_END_TINT;
    public static final int ASPECT_WEATHER_BITS = FEATURE_STORM_FOG;

    private FrameFlags() {
    }
}
