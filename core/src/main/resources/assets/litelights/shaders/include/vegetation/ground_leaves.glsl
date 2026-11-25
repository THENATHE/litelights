#ifndef LITELIGHTS_GROUND_LEAVES_GLSL
#define LITELIGHTS_GROUND_LEAVES_GLSL

#include <litelights:frame.glsl>
#include <litelights:water/uniforms.glsl>
#include <litelights:water/common.glsl>
#include <litelights:leaf_scatter.glsl>

// Leaves under trees (Vegetation page): fallen leaves lying on the top of grass, dirt, moss and paths
// under and around trees, the floating leaves' scatter on the ground. Where they may lie, the
// canopy's colour and its density come from the column map's ground leaves region (GroundLeavesGrid,
// FluidDataTexture.GROUND_LEAVES_Y); the CPU has already left out snow, leaf litter, water and
// anything under a roof. Called on the block texture's colour before lighting, so a leaf is lit,
// shadowed and fogged like the ground it lies on.

const int LITE_GROUND_LEAVES_Y = 1152;
// Leaves thin out to none over this range, in blocks: farther off a leaf is a pixel or less.
const float LITE_GROUND_LEAVES_FADE_START = 48.0;
const float LITE_GROUND_LEAVES_FADE_END = 96.0;
// A ground face needs at least this much sky light (16 a level). Under the thickest canopy the
// ground keeps about 11; it rules out a cave floor that happens to sit at the ground's height
// modulo 16 in the same column.
const float LITE_GROUND_LEAVES_SKY_LIGHT = 112.0;
// Each leaf's shade, as a multiple of the canopy's colour: two greens, one yellowing and one dried
// brown, as the floating leaves have one brown in four. Lighter and warmer than on water: a leaf in
// the canopy's own green barely shows on green grass, least of all in the tree's shade.
const vec3 LITE_GROUND_LEAF_SHADES[4] = vec3[4](vec3(1.0), vec3(1.25), vec3(1.35, 1.2, 0.6), vec3(1.15, 0.8, 0.45));
// Leaves gather thicker on the ground than on water, where they drift apart: the same falloff from
// the trees, with 1.6 times as many leaves at every distance.
const float LITE_GROUND_LEAVES_THICKER = 1.6;

// color: the block texture times the vertex colour, before lighting. vertexColor: the ground's tint
// times its ambient occlusion; the region holds the canopy's colour over that tint, so the product is
// the canopy's colour with the ground's occlusion.
void lite_ground_leaves(inout vec4 color, vec4 vertexColor, vec3 position, vec3 normal, vec2 lightCoord) {
    if (!lite_world_pass() || !lite_water(LITE_WATER_GROUND_LEAVES) || normal.y < 0.9 || lightCoord.y < LITE_GROUND_LEAVES_SKY_LIGHT) {
        return;
    }
    float distance = length(position);
    if (distance >= LITE_GROUND_LEAVES_FADE_END) {
        return;
    }
    vec3 local = lite_block_local(position);
    ivec2 column = LiteCameraBlock.xz + ivec2(floor(local.xz));
    if (!lite_fluid_map_contains(column)) {
        return;
    }
    ivec4 texel = ivec4(texelFetch(LiteFluidData, (column & 255) + ivec2(0, LITE_GROUND_LEAVES_Y), 0) * 255.0 + 0.5);
    int density = texel.a & 15;
    // The face must be the top of the column's ground block: a top face whose block is at that height.
    int blockY = LiteCameraBlock.y + int(floor(local.y - 0.03));
    if (density == 0 || (blockY & 15) != (texel.a >> 4)) {
        return;
    }
    ivec2 inMap = column - LiteWaterFlags.yz;
    int toEdge = min(min(inMap.x, inMap.y), min(255 - inMap.x, 255 - inMap.y));
    float fade = (1.0 - smoothstep(LITE_GROUND_LEAVES_FADE_START, LITE_GROUND_LEAVES_FADE_END, distance)) * smoothstep(0.0, 12.0, float(toEdge));
    uint look;
    ivec2 leafTexel;
    if (lite_leaf_scatter(lite_water_anchored(position), float(density) / 15.0 * LITE_GROUND_LEAVES_THICKER * fade, 0.0, 0.0, look, leafTexel) == 0.0) {
        return;
    }
    vec3 canopy = clamp(vec3(texel.rgb) / 64.0 * vertexColor.rgb, 0.0, 1.0);
    vec3 shade = LITE_GROUND_LEAF_SHADES[int((look >> 5) & 3u)] * (0.88 + 0.12 * lite_water_hash(leafTexel));
    color.rgb = min(canopy * shade, vec3(1.0));
}

#endif
