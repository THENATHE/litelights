#ifndef LITELIGHTS_HOOK_VEGETATION_GLSL
#define LITELIGHTS_HOOK_VEGETATION_GLSL

// Owned by the vegetation stream: grass, plants and leaves swaying in a world-space wind.
//
// Called from the terrain vertex shaders (vanilla and Sodium) in world passes. The offset is a
// function of the vertex's own unmoved world position and the time, so a vertex shared by two
// leaf blocks moves the same way in both, and the wind runs on unbroken across chunk borders.
// docs/ARCHITECTURE.md (Vegetation) explains the inputs; WindField in the unit tests
// (src/test/.../render/feature/vegetation) is the same wind in Java, where its continuity is checked. Keep the two
// in step.

#include <litelights:frame.glsl>
#include <litelights:material.glsl>

// LiteFeatureValuesD.y: the wind's heading in radians, from particles-weather's Wind, so plants lean
// the way the rain and dust blow. It turns slowly and never jumps.
// LiteFeatureValuesD.z: which sway switches are on (bits below), 0 when none is.
// LiteFeatureValuesD.w: amplitude scale, 1 normally (QA raises it to make the anchoring obvious).
const int LITE_SWAY_GRASS = 1;
const int LITE_SWAY_PLANTS = 2;
const int LITE_SWAY_LEAVES = 4;

// Beyond this the motion is under a pixel at 1440p, so it fades out and far vertices skip the work.
const float LITE_SWAY_FADE_START = 64.0;
const float LITE_SWAY_FADE_END = 96.0;

// Tips of short grass move about 1.5 texture pixels in calm weather; leaves about half a pixel.
const float LITE_SWAY_PLANT_AMPLITUDE = 0.09;
const float LITE_SWAY_LEAF_AMPLITUDE = 0.035;

// The wind repeats every 256 blocks and every hour. Every spatial frequency below is a whole number
// of cycles per 256 blocks and every time frequency a whole number of cycles per hour, so wrapping
// the world position to 256 and the clock to 3600 s (LiteTime.y) never shows as a jump.
const float LITE_WIND_PERIOD_BLOCKS = 256.0;
const float LITE_WIND_PERIOD_SECONDS = 3600.0;
const float LITE_TAU = 6.28318530718;

vec2 lite_wind_wave(vec2 cyclesPerPeriod) {
    return cyclesPerPeriod * (LITE_TAU / LITE_WIND_PERIOD_BLOCKS);
}

vec3 lite_wind_wave3(vec3 cyclesPerPeriod) {
    return cyclesPerPeriod * (LITE_TAU / LITE_WIND_PERIOD_BLOCKS);
}

float lite_wind_rate(float cyclesPerHour) {
    return cyclesPerHour * (LITE_TAU / LITE_WIND_PERIOD_SECONDS);
}

// The prevailing wind: the world's wind direction, and gust bands that roll across the land at
// four to five blocks a second. Returns xy = direction, z = gust strength 0.1..1.
vec3 lite_wind_gust(vec2 worldXZ, float seconds) {
    float heading = LiteFeatureValuesD.y;
    vec2 direction = vec2(cos(heading), sin(heading));
    float wave = 0.6 * sin(dot(lite_wind_wave(vec2(5.0, 2.0)), worldXZ) - lite_wind_rate(378.0) * seconds)
        + 0.4 * sin(dot(lite_wind_wave(vec2(3.0, 7.0)), worldXZ) - lite_wind_rate(429.0) * seconds);
    return vec3(direction, 0.55 + 0.45 * wave);
}

// How grass and plants bend, per block of height: a lean with the wind that swings back and forth,
// plus a smaller sideways flutter. Their wavelengths (about ten blocks) put neighbouring plants
// out of step while the corners of one plant move nearly together, so its texture barely
// stretches. Horizontal only.
vec3 lite_wind_plant(vec3 world, float seconds) {
    vec3 gust = lite_wind_gust(world.xz, seconds);
    vec2 across = vec2(-gust.y, gust.x);
    float swing = sin(dot(lite_wind_wave(vec2(19.0, 13.0)), world.xz) + lite_wind_rate(1260.0) * seconds);
    float flutter = sin(dot(lite_wind_wave(vec2(-17.0, 23.0)), world.xz) + lite_wind_rate(1776.0) * seconds);
    vec2 bend = gust.xy * gust.z * (0.55 + 0.45 * swing) + across * gust.z * 0.2 * flutter;
    return vec3(bend.x, 0.0, bend.y);
}

// How leaves move: a gentle three-dimensional shimmer that depends on height too, so a canopy
// ripples rather than sliding as one piece.
vec3 lite_wind_leaves(vec3 world, float seconds) {
    vec3 gust = lite_wind_gust(world.xz, seconds);
    float along = sin(dot(lite_wind_wave3(vec3(23.0, 17.0, 31.0)), world) + lite_wind_rate(917.0) * seconds);
    float lift = sin(dot(lite_wind_wave3(vec3(-19.0, 37.0, 27.0)), world) + lite_wind_rate(1318.0) * seconds);
    float across = sin(dot(lite_wind_wave3(vec3(29.0, -23.0, -41.0)), world) + lite_wind_rate(1121.0) * seconds);
    vec2 horizontal = gust.xy * (0.4 + 0.6 * along) + vec2(-gust.y, gust.x) * 0.5 * across;
    return vec3(horizontal.x, 0.4 * lift, horizontal.y) * gust.z;
}

// Which corner of its sprite a vertex is at: -1 (left or top edge) or +1 (right or bottom edge) on
// each axis. Sodium stores it with the vertex (_vert_tex_diffuse_coord_bias). Vanilla's chunk meshes
// keep the model's vertex order, and the baker starts every face at the texture's top-left corner
// and goes round anticlockwise (top-left, bottom-left, bottom-right, top-right), so the corner
// follows from the vertex's index within its section's mesh (terrain.vsh); VegetationMaterials
// only lets plants sway whose quads keep that order. A face that maps its texture mirrored left to
// right (half of a crop's faces) has its left and right swapped; vanilla's chunk builder marks
// those quads with a vertex colour alpha of 254 (MirroredQuads), and vertexAlpha undoes the swap.
vec2 lite_vegetation_corner(int vertexInMesh, float vertexAlpha) {
    int corner = vertexInMesh & 3;
    float side = corner < 2 ? -1.0 : 1.0;
    // 254 / 255 is 0.9961; anything below the midpoint to 1.0 is the mark.
    if (vertexAlpha < 0.998) {
        side = -side;
    }
    return vec2(side, (corner == 1 || corner == 2) ? 1.0 : -1.0);
}

// The material of the sprite a vertex belongs to. The vertex's own atlas coordinate lies on its
// sprite's edge, so the material is read half a material texel in from the corner, where it is
// sure to be this sprite's.
int lite_vegetation_material(vec2 atlasUv, vec2 corner) {
    vec2 halfTexel = 0.5 / vec2(textureSize(LiteMaterials, 0));
    return lite_material(atlasUv - corner * halfTexel);
}

// How far up its plant a vertex is, in blocks: 0 at the ground, about 1 at the top of a one-block
// plant, about 2 at the top of a two-block one. topOfSprite says which end of the texture the vertex
// is at, which settles the block it belongs to when it sits exactly on a block boundary. Random
// model offsets (up to 0.2 below the block) and crops sunk 1/16 into farmland stay inside the
// 0.25 margins.
float lite_plant_height(float blockLocalY, bool topOfSprite, bool upperHalf) {
    float plantBase = topOfSprite ? ceil(blockLocalY - 0.25) - 1.0 : floor(blockLocalY + 0.25);
    float height = blockLocalY - plantBase;
    if (upperHalf) {
        // The lower half's top moves by its full height; the upper half carries on from there.
        return 1.0 + height;
    }
    if (!topOfSprite && height < 0.3) {
        // A plant's foot (or a potted plant's, a quarter block up) stays where it is. Bottom edges
        // higher than that belong to parts that sit up on the stem, and move with it.
        return 0.0;
    }
    return max(height, 0.0);
}

// position: camera-relative, unmoved. atlasUv: the vertex's block-atlas coordinate. corner: see
// lite_vegetation_corner. Returns the camera-relative offset to add.
vec3 lite_vegetation_offset(vec3 position, vec2 atlasUv, vec2 corner) {
    int switches = int(LiteFeatureValuesD.z);
    if (switches == 0) {
        return vec3(0.0);
    }
    float fade = 1.0 - smoothstep(LITE_SWAY_FADE_START, LITE_SWAY_FADE_END, length(position));
    if (fade <= 0.0) {
        return vec3(0.0);
    }

    int material = lite_vegetation_material(atlasUv, corner);

    bool leaves = material == LITE_MATERIAL_LEAVES;
    bool grass = material == LITE_MATERIAL_GRASS || material == LITE_MATERIAL_GRASS_UPPER;
    bool plant = material == LITE_MATERIAL_PLANT || material == LITE_MATERIAL_PLANT_UPPER;
    int needed = leaves ? LITE_SWAY_LEAVES : grass ? LITE_SWAY_GRASS : plant ? LITE_SWAY_PLANTS : 0;
    if ((switches & needed) == 0) {
        return vec3(0.0);
    }

    vec3 blockLocal = lite_block_local(position);
    vec3 world = vec3(LiteCameraBlock.xyz & ivec3(255)) + blockLocal;
    float seconds = LiteTime.y;
    // A little stronger in rain, more again in thunder.
    float weather = 1.0 + 0.35 * LiteWeather.x + 0.35 * LiteWeather.y;
    float strength = fade * weather * LiteFeatureValuesD.w;

    if (leaves) {
        return lite_wind_leaves(world, seconds) * (LITE_SWAY_LEAF_AMPLITUDE * strength);
    }
    bool upperHalf = material == LITE_MATERIAL_GRASS_UPPER || material == LITE_MATERIAL_PLANT_UPPER;
    float height = lite_plant_height(blockLocal.y, corner.y < 0.0, upperHalf);
    return lite_wind_plant(world, seconds) * (height * LITE_SWAY_PLANT_AMPLITUDE * strength);
}

// Hanging lanterns, chains and hanging signs swinging in the same wind (lanterns099).
#include <litelights:vegetation/hanging.glsl>

// Leaves under trees: called by the terrain fragment shaders (vanilla's and Sodium's) on the block
// texture's colour, after the cut-out test and before lighting, outside Improved Transparency's
// alpha-only stages. Only the terrain shaders (they name their atlas sampler in LITE_HOST_BLOCK_ATLAS)
// have the real version; it reads the water stream's LiteFluidData, so it adds no texture unit.
#if defined(LITELIGHTS_SHADOW_GLSL) && defined(LITELIGHTS_MATERIAL_GLSL) && defined(LITE_HOST_BLOCK_ATLAS)
#include <litelights:vegetation/ground_leaves.glsl>
#else
void lite_ground_leaves(inout vec4 color, vec4 vertexColor, vec3 position, vec3 normal, vec2 lightCoord) {
}
#endif

#endif
