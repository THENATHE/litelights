#ifndef LITELIGHTS_FRAME_GLSL
#define LITELIGHTS_FRAME_GLSL

// Per-frame values shared by every LiteLights shader. Written once per frame by
// com.thenathe.litelights.render.frame.FrameUniforms; the field order there must match this block.
//
// "Camera-relative" positions are world positions minus the camera position, which is what
// vanilla's and Sodium's vertex shaders already compute before the view rotation.
layout(std140) uniform LiteFrame {
    mat4 LiteSunShadowMatrix;        // camera-relative position -> sun shadow clip space (current map)
    mat4 LiteSunShadowMatrixPrev;    // the same for the map being faded out after a swap
    mat4 LiteMobShadowMatrix;        // camera-relative position -> mob shadow clip space
    mat4 LiteViewProjection;         // camera-relative position -> clip space, this frame (unjittered)
    mat4 LiteViewProjectionPrev;     // last frame's, rebased onto this frame's camera (for reprojection)
    mat4 LiteInverseViewProjection;  // clip space -> camera-relative position, this frame
    vec4 LiteSunDirection;           // xyz unit vector toward the sun, w = sin(sun elevation)
    vec4 LiteMoonDirection;          // xyz unit vector toward the moon, w = sin(moon elevation)
    vec4 LiteShadowLightDirection;   // xyz toward the body casting shadows, w = 0 sun, 1 moon
    vec4 LiteSunColor;               // rgb direct light colour (vibrancy applied), a = daylight 0..1
    vec4 LiteAmbientColor;           // rgb tint of sky light in shadow, a = night factor 0..1
    ivec4 LiteCameraBlock;           // camera block position, w = dimension kind (see LITE_DIMENSION_*)
    vec4 LiteCameraFraction;         // camera position minus LiteCameraBlock; w unused
    vec4 LiteTime;                   // x day fraction 0..1, y seconds wrapped at 3600, z the falling-star preview's elevation in radians (0 = no preview), w its bearing (radians from north, turning east)
    vec4 LiteWeather;                // x rain 0..1, y thunder 0..1; z, w unused
    vec4 LiteShadowParams;           // x darkness, y sky light a full shadow takes (ShadowDepth), z sunlight colour, w reach in blocks
    vec4 LiteShadowMapInfo;          // x sun map size, y blocks per sun texel, z mob map size, w blocks per mob texel
    vec4 LiteShadowFade;             // x weight of the current sun map (1 = no fade), y light strength 0..1, z unused, w shadow offset in texels
    vec4 LiteScreen;                 // xy framebuffer size, zw reciprocal
    ivec4 LiteFlags;                 // x LITE_FEATURE_* bits, y LITE_LOCK_* bits, z LITE_PASS_*, w material grid shift
    vec4 LiteFeatureValuesA;         // x where LiteLights' water hands its depth look over to Voxy's, else 0; y where LiteLights' own land ends while Voxy draws beyond it, else 0 (far terrain); z, w unused (water keeps its values in its own block)
    vec4 LiteFeatureValuesB;         // unused (sky-fog's values follow the block, below)
    vec4 LiteFeatureValuesC;         // light-post: x glint smoothness, y glint strength, z night tone strength, w ray landing strength
    vec4 LiteFeatureValuesD;         // x unused; y wind heading in radians (particles-weather); z sway switches, w sway amplitude (vegetation)
    // Sky and fog (sky-fog stream, render.feature.skyfog). Positions and heights are in blocks.
    vec4 LiteHazeColor;              // rgb haze colour lit for the time of day, a = haze density per block at its base height
    vec4 LiteHazeShape;              // x haze height falloff per block, y camera height above the haze base, z ground mist amount, w camera height above the ground
    vec4 LiteFogRange;               // x, y render-distance fog start and end (cylindrical), z camera sky exposure 0..1, w ground mist colour lift
    vec4 LiteSunGlow;                // rgb glow colour around the sun in the haze, a = how tightly it gathers (phase exponent)
    vec4 LiteStormFog;               // rgb storm fog colour, a = storm fog density per block
    vec4 LiteSkyZenith;              // rgb realistic sky colour overhead, a = star brightness 0..1
    vec4 LiteSkyInfo;                // x moon phase 0..7, y night number (for shooting-star nights), z star rotation in radians, w storm cover of the sky 0..1
    vec4 LiteEndTint;                // rgb End colour tint (1 = none), a = End sky aura strength
    // Weather on glass (particles-weather, render.feature.weather.GlassWeather; see include/glass/weather.glsl).
    vec4 LiteGlassCold;              // x frost 0..1, y snow 0..1, z dust 0..1, w 1 for red sand
    vec4 LiteGlassInfo;              // x texels per block (0 = nothing to draw); y, z, w unused
};

// LiteFlags.x
// In bit order, with their owner; free: 3 and 10 to 13. Mirrored in FrameFlags.
const int LITE_FEATURE_SHADOWS = 1;                // core-render
const int LITE_FEATURE_ENHANCED_LIGHT = 2;         // light-post (set by core-render)
const int LITE_FEATURE_MOB_SHADOWS = 4;            // core-render
const int LITE_FEATURE_CAMERA_UNDERWATER = 16;     // core-render
const int LITE_FEATURE_MATERIALS = 32;             // core-render
const int LITE_FEATURE_SUN_MAP = 64;               // core-render
const int LITE_FEATURE_MOB_MAP = 128;              // core-render
const int LITE_FEATURE_SUN_MAP_FADING = 256;       // core-render
const int LITE_FEATURE_ENHANCED_SHADOWS = 512;     // core-render
const int LITE_FEATURE_MARK_NEAR = 1 << 10;        // far-terrain: opaque LiteLights surfaces write LITE_NEAR_ALPHA (see far_terrain.glsl)
const int LITE_FEATURE_STORM_FOG = 1 << 14;        // sky-fog
const int LITE_FEATURE_DARK_DISC = 1 << 15;        // sky-fog: camera below the horizon height, the sky below it is dark
const int LITE_FEATURE_SHOOTING_STARS = 1 << 16;   // sky-fog
const int LITE_FEATURE_REALISTIC_SKY = 1 << 17;    // sky-fog
const int LITE_FEATURE_LITE_CLOUDS = 1 << 18;      // sky-fog
const int LITE_FEATURE_LITE_SKY = 1 << 19;         // sky-fog: LiteLights draws the sun, moon and stars
const int LITE_FEATURE_DEBUG_SHADOWS = 1 << 20;    // core-render: -Dlitelights.debug=shadows shows the shadow lookup
const int LITE_FEATURE_LITE_FOG = 1 << 21;         // sky-fog: lite_fog replaces vanilla's fog (Atmospheric or Off)
const int LITE_FEATURE_HAZE = 1 << 22;             // sky-fog: atmospheric haze (Atmospheric); off means fog Off
const int LITE_FEATURE_GROUND_MIST = 1 << 23;      // sky-fog
const int LITE_FEATURE_BLOOM_MASK = 1 << 24;       // light-post: write the emissive mask into alpha for bloom
const int LITE_FEATURE_NIGHT_TONE = 1 << 25;       // light-post: cool moonlit sky light at night
const int LITE_FEATURE_TERRAIN_GLINT = 1 << 26;    // light-post: blocks and the water surface catch the sun
const int LITE_FEATURE_UNDERWATER_GLINT = 1 << 27; // light-post: glint while the camera is under water
const int LITE_FEATURE_RAY_LANDING = 1 << 28;      // light-post: light rays brighten the surfaces they land on
const int LITE_FEATURE_SKYLESS = 1 << 29;          // sky-fog: no sky light (Nether, End, cave dimensions)
const int LITE_FEATURE_END_TINT = 1 << 30;         // sky-fog

// LiteFlags.y
const int LITE_LOCK_SHADOWS = 1;
const int LITE_LOCK_CAUSTICS = 2;
const int LITE_LOCK_WATER_SURFACE = 4;
const int LITE_LOCK_GLINT = 8;
const int LITE_LOCK_LIGHT_RAYS = 16;

// LiteFlags.z: 1 in a world pass, 2 for the first-person hand, 0 (neutral) anywhere else. Anything
// that is not a world pass must look exactly like vanilla (GUI items, the hand, map previews).
const int LITE_PASS_WORLD = 1;
const int LITE_PASS_HAND = 2;

// LiteCameraBlock.w
const int LITE_DIMENSION_OVERWORLD = 0;
const int LITE_DIMENSION_NETHER = 1;
const int LITE_DIMENSION_END = 2;
const int LITE_DIMENSION_MODDED = 3;

bool lite_feature(int bit) {
    return (LiteFlags.x & bit) != 0;
}

bool lite_locked(int bit) {
    return (LiteFlags.y & bit) != 0;
}

bool lite_world_pass() {
    return LiteFlags.z == LITE_PASS_WORLD;
}

// Position relative to the camera's block corner. Unlike a full world position this keeps its
// sub-block precision at any distance from the world origin, so fract() on it is exact.
vec3 lite_block_local(vec3 cameraRelative) {
    return cameraRelative + LiteCameraFraction.xyz;
}

vec3 lite_camera_relative(vec3 blockLocal) {
    return blockLocal - LiteCameraFraction.xyz;
}

// Where the eye is this frame, camera-relative. View bobbing moves it off the camera position by up
// to about a tenth of a block (the level's projection carries the bob's offset), so directions toward
// what is seen start here. It is the point the projection sends to clip w = 0, which the inverse
// matrix's third column holds. The neutral block's matrices are identity: there it is the camera.
vec3 lite_view_origin() {
    vec4 eye = LiteInverseViewProjection[2];
    return abs(eye.w) > 1.0e-6 ? eye.xyz / eye.w : vec3(0.0);
}

#endif
