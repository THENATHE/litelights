package com.thenathe.litelights.render.feature.water;

/** Bit values for LiteWaterFlags.x in shaders/include/water/uniforms.glsl. Keep the two lists in step. */
public final class WaterFlags {
    public static final int REALISTIC_COLOURS = 1;
    public static final int BIOME_DEPTH_COLOUR = 2;
    public static final int REFLECTIONS = 4;
    public static final int ICE_REFLECTIONS = 8;
    public static final int SHORE_FOAM = 16;
    public static final int LAVA_CRUST = 32;
    public static final int FLOATING_LEAVES = 64;
    public static final int CAUSTICS = 128;
    public static final int LARGE_WATER = 256;
    public static final int LARGE_LAVA = 512;
    public static final int FALLING_WATER = 1024;
    /** The column map holds data for the window it says it covers. */
    public static final int COLUMN_MAP = 2048;
    /** Enhanced colours: Natural's shading over the water texture's own pixels. */
    public static final int ENHANCED_COLOURS = 4096;
    /** The water the game draws is vanilla's own texture, so the bundled large textures may stand in for it. */
    public static final int VANILLA_WATER_SPRITES = 8192;
    /** Foam motion is Drifting: foam on flowing water travels downstream (the flow is in the rain tops' texels). */
    public static final int DRIFTING_FOAM = 16384;
    /** Ocean waves: swell on the sea and swash up the beach (the shore field is in the fluid data's third region). */
    public static final int OCEAN_WAVES = 32768;
    /** Wakes: the wake field (the fluid data's fifth region) holds ripples or foam to draw. */
    public static final int WAKES = 65536;

    /** Effects that read the column map; without one of these on, the map isn't built at all. */
    public static final int NEEDS_COLUMN_MAP = SHORE_FOAM | LAVA_CRUST | FLOATING_LEAVES | CAUSTICS | FALLING_WATER | WAKES;
    /** The foam that Drifting moves: without either, the flow isn't recorded at all. */
    public static final int DRIFTING_FOAM_MOVES = SHORE_FOAM | FALLING_WATER;
    /** Effects that read the opaque scene copied before translucent terrain. */
    public static final int NEEDS_SCENE_COPY = BIOME_DEPTH_COLOUR | REFLECTIONS | ICE_REFLECTIONS;

    private WaterFlags() {
    }
}
