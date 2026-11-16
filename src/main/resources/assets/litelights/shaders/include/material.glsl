#ifndef LITELIGHTS_MATERIAL_GLSL
#define LITELIGHTS_MATERIAL_GLSL

#include <litelights:frame.glsl>

// The material texture is laid out like the block atlas: each sprite's area holds that sprite's
// material id, so terrain shaders read the material from the texture coordinate they already have.
// It is stored at a lower resolution than the atlas (LiteFlags.w is the log2 of the reduction),
// which is safe because the stitcher aligns every sprite to that grid.
uniform sampler2D LiteMaterials;

const int LITE_MATERIAL_DEFAULT = 0;
const int LITE_MATERIAL_WATER = 1;
const int LITE_MATERIAL_LAVA = 2;
const int LITE_MATERIAL_ICE = 3;
const int LITE_MATERIAL_GLASS = 4;
const int LITE_MATERIAL_LEAVES = 5;
const int LITE_MATERIAL_PLANT = 6;
const int LITE_MATERIAL_GLOWING = 7;
const int LITE_MATERIAL_METAL = 8;
const int LITE_MATERIAL_SNOW = 9;
const int LITE_MATERIAL_SAND = 10;
const int LITE_MATERIAL_FIRE = 11;
// Vegetation (see hooks/vegetation.glsl). Plants that sway are split by switch and by whether the
// sprite is the upper half of a two-block plant; plants whose model can't sway (flat, hanging or
// bush-shaped) are PLANT_STILL.
const int LITE_MATERIAL_GRASS = 12;
const int LITE_MATERIAL_GRASS_UPPER = 13;
const int LITE_MATERIAL_PLANT_UPPER = 14;
const int LITE_MATERIAL_PLANT_STILL = 15;

int lite_material(vec2 atlasUv) {
    if (!lite_feature(LITE_FEATURE_MATERIALS)) {
        return LITE_MATERIAL_DEFAULT;
    }
    ivec2 size = textureSize(LiteMaterials, 0);
    ivec2 cell = clamp(ivec2(atlasUv * vec2(size)), ivec2(0), size - 1);
    return int(texelFetch(LiteMaterials, cell, 0).r * 255.0 + 0.5);
}

bool lite_material_is_foliage(int material) {
    return material == LITE_MATERIAL_LEAVES || material == LITE_MATERIAL_PLANT
        || (material >= LITE_MATERIAL_GRASS && material <= LITE_MATERIAL_PLANT_STILL);
}

bool lite_material_is_emissive(int material) {
    return material == LITE_MATERIAL_GLOWING || material == LITE_MATERIAL_LAVA || material == LITE_MATERIAL_FIRE;
}

#endif
