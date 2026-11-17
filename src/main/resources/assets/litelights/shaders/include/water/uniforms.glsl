#ifndef LITELIGHTS_WATER_UNIFORMS_GLSL
#define LITELIGHTS_WATER_UNIFORMS_GLSL

// The water stream's own uniforms. Written once per frame by
// com.thenathe.litelights.render.feature.water.WaterUniforms; the field order there must match this block.
layout(std140) uniform LiteWater {
    vec4 LiteWaterSkyColor;       // rgb sky colour overhead, a = 1 when the dimension has a sky
    vec4 LiteWaterHorizonColor;   // rgb fog colour at the horizon
    vec4 LiteWaterStillSprite;    // block atlas rectangles (u0, v0, u1, v1) of the four fluid sprites
    vec4 LiteWaterFlowSprite;
    vec4 LiteLavaStillSprite;
    vec4 LiteLavaFlowSprite;
    vec4 LiteFluidStripLayout;    // large textures in LiteFluidData: x still frame width, y flow frame width
                                  // (texture u), z still frame height, w flow frame height (texture v)
    vec4 LiteWaterFrames;         // x still frame, y flow frame (fraction = blend toward the next frame),
                                  // z still frame count, w flow frame count
    vec4 LiteLavaFrames;          // the same for lava
    vec4 LiteWaterParams;         // x reflection strength, y reflection steps, z water tile blocks, w lava tile blocks
    vec4 LiteWaterDepth;          // device depth = x + y / view depth; z, w unused
    ivec4 LiteWaterFlags;         // x LITE_WATER_* bits, y/z column map origin (block x, z), w level min y
    ivec4 LiteWaterTileOrigin;    // camera block modulo the still (xy) and flowing (zw) water tile
    ivec4 LiteLavaTileOrigin;     // the same for lava
    ivec4 LiteWaterAnchor;        // xy camera block modulo 2048, for procedural patterns
    vec4 LiteWaterOverlaySprite;  // the water overlay sprite (sides beside glass and leaves)
    vec4 LiteIceSprites[3];       // ice, packed ice and blue ice
    vec4 LiteFluidStripColumns;   // texture u where each column of frames starts: water still, water flow,
                                  // lava still, lava flow
    vec4 LiteAlternateFluidSprites[4]; // the same fluid sprites from another mod's namespace (water still,
                                       // water flow, lava still, lava flow), or off the atlas when none
    vec4 LiteWaterSpriteMeans[7];      // rgb average colour of: water still, flow, overlay, the other
                                       // namespace's still and flow, the large still and flow textures
    vec4 LiteWaveTrains[4];       // ocean waves out at sea: xy wave vector (radians per block), z phase now,
                                  // w share of the swell (0 = unused); see OceanSwell
    vec4 LiteWaveInfo;            // x sea state 0 (calm) to 1 (storm), y the shore waves' phase now (whole waves
                                  // counted up to 1024), z their wave number (radians per block), w Shore
                                  // run-up (1 to 2: how far the swash runs in fair weather; 1 = default)
    vec4 LiteWaveWind;            // xy the wind's direction; z Wave foam (0.25 to 2: the surf's and the
                                  // swash's foam lines thinner or thicker; 1 = default); w unused
    vec4 LiteGlassSprites[3];          // block atlas rectangles of snow, sand and red sand (weather on
                                       // glass draws in them), or off the atlas while unknown
    ivec4 LiteWakeWindow;         // wakes: xy the field's first cell less the camera block's (4 cells a block);
                                  // z, w unused
    vec4 LiteWakeParams;          // wakes: x 1 to draw their foam, y the partial tick; z, w unused
};

// LiteWaterFlags.x
const int LITE_WATER_REALISTIC = 1;
const int LITE_WATER_BIOME_DEPTH = 2;
const int LITE_WATER_REFLECTIONS = 4;
const int LITE_WATER_ICE_REFLECTIONS = 8;
const int LITE_WATER_SHORE_FOAM = 16;
const int LITE_WATER_LAVA_CRUST = 32;
const int LITE_WATER_FLOATING_LEAVES = 64;
const int LITE_WATER_CAUSTICS = 128;
const int LITE_WATER_LARGE_WATER = 256;
const int LITE_WATER_LARGE_LAVA = 512;
const int LITE_WATER_FALLING_WATER = 1024;
const int LITE_WATER_COLUMN_MAP = 2048;
const int LITE_WATER_ENHANCED = 4096;
const int LITE_WATER_VANILLA_SPRITES = 8192;
const int LITE_WATER_DRIFTING_FOAM = 16384;
const int LITE_WATER_OCEAN_WAVES = 32768;
const int LITE_WATER_WAKES = 65536;

// Two samplers of its own: the OpenGL backend has twelve texture units per draw, and Sodium's
// translucent terrain under Improved Transparency already uses ten before these (see
// docs/ARCHITECTURE.md, "Texture units").
//
// The opaque scene, copied just before translucent terrain is drawn: rgb its colour, a its view
// depth in blocks (65000 for the sky). While no copy is valid (the solid pass, frames without water)
// a 1x1 placeholder is bound instead.
uniform sampler2D LiteScene;
// The column map in the corner (one texel per block column around the camera, see FluidColumnGrid:
// surface height, shore and leaf data), under it the rain tops (RainTopGrid) with the surface flow in
// their blue and alpha (FluidFlowGrid), under those the shore field for the ocean waves (ShoreField)
// and the wakes (WakeField), and once loaded the large water and lava textures beside them.
uniform sampler2D LiteFluidData;
// The block atlas, to split a shaded fluid colour back into its texture and the rest. Shaders that
// sample the atlas themselves (terrain, block models, Sodium) name their own sampler in
// LITE_HOST_BLOCK_ATLAS before the hooks, so this costs them no texture unit; the same texture
// and the same filtering the fluid was drawn with.
#ifdef LITE_HOST_BLOCK_ATLAS
    #define LiteWaterAtlas LITE_HOST_BLOCK_ATLAS
#else
uniform sampler2D LiteWaterAtlas;
#endif

bool lite_water(int bit) {
    return (LiteWaterFlags.x & bit) != 0;
}

bool lite_in_sprite(vec2 uv, vec4 rect) {
    return all(greaterThanEqual(uv, rect.xy)) && all(lessThan(uv, rect.zw));
}

// Water and lava are recognised by their sprites rather than the material texture: the fluid
// sprites' rectangles are known exactly, and nothing else uses them.
bool lite_is_water_sprite(vec2 uv) {
    return lite_in_sprite(uv, LiteWaterStillSprite) || lite_in_sprite(uv, LiteWaterFlowSprite) || lite_in_sprite(uv, LiteWaterOverlaySprite)
        || lite_in_sprite(uv, LiteAlternateFluidSprites[0]) || lite_in_sprite(uv, LiteAlternateFluidSprites[1]);
}

bool lite_is_lava_sprite(vec2 uv) {
    return lite_in_sprite(uv, LiteLavaStillSprite) || lite_in_sprite(uv, LiteLavaFlowSprite)
        || lite_in_sprite(uv, LiteAlternateFluidSprites[2]) || lite_in_sprite(uv, LiteAlternateFluidSprites[3]);
}

bool lite_is_ice_sprite(vec2 uv) {
    return lite_in_sprite(uv, LiteIceSprites[0]) || lite_in_sprite(uv, LiteIceSprites[1]) || lite_in_sprite(uv, LiteIceSprites[2]);
}

#endif
