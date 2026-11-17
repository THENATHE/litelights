#ifndef LITELIGHTS_WEATHER_SNOW_COVER_GLSL
#define LITELIGHTS_WEATHER_SNOW_COVER_GLSL

#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:water/uniforms.glsl>
#include <litelights:water/common.glsl>

// Snowy leaves and ground (Weather details, snowleaves099). In biomes where it snows, leaves open
// to the sky frost over while it snows, the leaf texture's brighter texels first, from the tops of
// the blocks down their sides, as snow caught in a canopy; and the bare ground under the trees,
// which vanilla's snow skips, takes a thin, patchy dusting. Called on the block texture's colour
// before lighting, so the snow is lit, shadowed and fogged like the block it lies on.
//
// Values from com.thenathe.litelights.render.feature.weather.SnowyLeaves (the LiteFrame block):
//   LiteSnowCover  x how much snow has gathered (0: none, one uniform test), y 1 while the settings
//                  screen previews snow, which then shows on canopies in any biome
// and from the water stream's column map, the snow cover region (SnowCoverGrid,
// FluidDataTexture.SNOW_COVER_Y): which columns are topped by leaves with nothing but sky over
// them, whether their biome snows, the canopy's and the bare ground's heights. The CPU has already
// left out canopies under a roof and ground that isn't bare.

const int LITE_SNOW_COVER_Y = 1408;
const int LITE_SNOW_CANOPY = 1;
const int LITE_SNOW_SNOWS = 2;
const int LITE_SNOW_CONIFER = 4;
const int LITE_SNOW_GROUND = 8;
// A face needs at least this much sky light (16 a level): the ground under the thickest canopy
// keeps about 11. It rules out most of what lies under the canopy before the texel fetch.
const float LITE_SNOW_SKY_LIGHT = 112.0;
// How much of the snow a leaf block takes by how far it is under its column's top leaf block: the
// top one all of it, the ones under it less, none four or more blocks down.
const float LITE_SNOW_DEPTH[4] = float[4](1.0, 0.55, 0.3, 0.15);
// Spruce and other needle-leaved trees hold more snow than broad leaves.
const float LITE_SNOW_CONIFER_HOLDS = 1.0;
const float LITE_SNOW_BROADLEAF_HOLDS = 0.7;
// The dusting under the trees is lighter than a snow layer: at most this much snow gathers there,
// against a canopy's 1, so about half of the ground under a full canopy stays bare, more by the
// trunk, and what lies shows a little of the ground through it.
const float LITE_SNOW_DUSTING_AMOUNT = 0.75;
const float LITE_SNOW_DUSTING = 0.85;
// Snow's colour before lighting: vanilla's snow texture is just off white.
const vec3 LITE_SNOW_WHITE = vec3(0.9, 0.93, 0.97);
// The brightest channel of the grass and leaf tints in snowy biomes (spruce's 0x619961 to snowy
// grass's 0x80B497), to tell a tinted face's shading from its tint.
const float LITE_SNOW_TINT_PEAK = 0.66;
// Beyond the first distance a texel is about a pixel at 1080p: the snow's texel edges soften into
// the share of the face they cover by the second, so far canopies don't shimmer.
const float LITE_SNOW_SOFT_START = 24.0;
const float LITE_SNOW_SOFT_END = 80.0;

// The vertex colour is a tint (green for grass and leaves, white for most blocks) times the face's
// shading and ambient occlusion. The shading alone, so the snow keeps the face's corners and sides
// darker without taking the green: a grey vertex colour is all shading, a green one is divided by
// the tints' usual brightness.
float lite_snow_shading(vec3 vertexColor) {
    float brightest = max(vertexColor.r, max(vertexColor.g, vertexColor.b));
    float darkest = min(vertexColor.r, min(vertexColor.g, vertexColor.b));
    bool tinted = brightest - darkest > 0.06 * brightest;
    return min(1.0, brightest / (tinted ? LITE_SNOW_TINT_PEAK : 1.0));
}

// 0 to 1, how white a texel is: need is how much snow it takes to turn it, amount how much there is.
float lite_snow_texel(float need, float amount, float distance) {
    float soft = mix(0.02, 0.3, smoothstep(LITE_SNOW_SOFT_START, LITE_SNOW_SOFT_END, distance));
    return smoothstep(need - soft, need + soft, amount);
}

// color: the block texture times the vertex colour, before lighting.
void lite_snow_cover_surface(inout vec4 color, vec4 vertexColor, vec3 position, vec3 normal, vec2 lightCoord, int material) {
    if (!lite_world_pass() || !lite_water(LITE_WATER_SNOW_COVER) || normal.y < -0.5 || lightCoord.y < LITE_SNOW_SKY_LIGHT) {
        return;
    }
    bool leaves = material == LITE_MATERIAL_LEAVES;
    if (!leaves && normal.y < 0.9) {
        return;
    }
    // Inside the block the face belongs to, so a face on a block boundary finds its own column.
    vec3 axis = sign(normal) * step(0.5, abs(normal));
    vec3 inside = lite_block_local(position) - axis * 0.02;
    ivec3 block = LiteCameraBlock.xyz + ivec3(floor(inside));
    if (!lite_fluid_map_contains(block.xz)) {
        return;
    }
    ivec4 texel = ivec4(texelFetch(LiteFluidData, (block.xz & 255) + ivec2(0, LITE_SNOW_COVER_Y), 0) * 255.0 + 0.5);
    int flags = texel.r;
    if ((flags & LITE_SNOW_CANOPY) == 0 || ((flags & LITE_SNOW_SNOWS) == 0 && LiteSnowCover.y <= 0.0)) {
        return;
    }

    float distance = length(position);
    ivec2 inMap = block.xz - LiteWaterFlags.yz;
    int toEdge = min(min(inMap.x, inMap.y), min(255 - inMap.x, 255 - inMap.y));
    float amount = LiteSnowCover.x * smoothstep(0.0, 12.0, float(toEdge));
    // The face's 1/16 texel, anchored to the world so the pattern stays put as the camera moves.
    ivec3 face = ivec3(floor(inside * 16.0)) + ivec3(LiteWaterAnchor.x, LiteCameraBlock.y, LiteWaterAnchor.y) * 16;
    float speckle = lite_water_hash(ivec2(face.x + face.y * 7, face.z - face.y * 13));
    float snow;

    if (leaves) {
        int depth = (texel.g - block.y) & 255;
        if (depth > 3) {
            return;
        }
        amount *= LITE_SNOW_DEPTH[depth] * ((flags & LITE_SNOW_CONIFER) != 0 ? LITE_SNOW_CONIFER_HOLDS : LITE_SNOW_BROADLEAF_HOLDS);
        // The leaf texture's own brightness: snow settles on the leaves, not in the gaps between.
        vec3 albedo = color.rgb / max(vertexColor.rgb, vec3(0.02));
        float bright = clamp((max(albedo.r, max(albedo.g, albedo.b)) - 0.25) / 0.6, 0.0, 1.0);
        float need;
        if (axis.y > 0.5) {
            need = 0.12 + 0.55 * (1.0 - bright) + 0.3 * speckle;
        } else {
            // Down a side from its top edge, further as more falls.
            float fromTop = 1.0 - (float(face.y & 15) + 0.5) / 16.0;
            need = 0.4 + 0.8 * fromTop + 0.45 * (1.0 - bright) + 0.3 * speckle;
        }
        snow = lite_snow_texel(need, amount * 1.3, distance) * (0.75 + 0.25 * bright);
    } else {
        if ((flags & LITE_SNOW_GROUND) == 0 || (block.y & 255) != texel.b) {
            return;
        }
        // Patchy: drifts of a two-block noise, thinner near the trunk and under the densest canopy.
        int trunk = (flags >> 4) & 3;
        float nearTrunk = trunk == 0 ? 1.0 : trunk == 1 ? 0.55 : trunk == 2 ? 0.2 : 0.0;
        float drift = lite_water_value_noise(lite_water_anchored(position) * 0.5 + vec2(17.0, 43.0));
        float need = 0.22 + 0.6 * drift + 0.25 * speckle + 0.35 * nearTrunk + 0.1 * float(texel.a) / 250.0;
        snow = lite_snow_texel(need, amount * LITE_SNOW_DUSTING_AMOUNT, distance) * LITE_SNOW_DUSTING;
    }
    if (snow <= 0.0) {
        return;
    }
    vec3 white = LITE_SNOW_WHITE * lite_snow_shading(vertexColor.rgb) * (0.94 + 0.06 * speckle);
    color.rgb = mix(color.rgb, white, snow);
}

#endif
