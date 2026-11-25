#ifndef LITELIGHTS_FARTERRAIN_FAR_SNOW_GLSL
#define LITELIGHTS_FARTERRAIN_FAR_SNOW_GLSL

// The visual snow's Snow on far terrain (render.feature.farterrain.FarLandSnow): the far land's
// LITE_FAR_SNOW pipelines, used only while it draws (Almanac 1.0.0 or later, the overworld, the row
// on). Far land stores the blocks a chunk had when it was last seen, snow and all; this shows the
// season's snow as Almanac has it now instead, in layers, and takes away snow that has thawed.
//
// Almanac says, per block column, the lowest height where the season's snow lies (its far snow
// line) and the height above which snow lies whatever the season (its bare line), and FarSnow keeps
// both around the camera in FarSnowLines with each column's climate. Between the two lines a
// column holds as many layers as its climate's shares (FarSnowShares, held to the same steps as
// the near snow's) are past its point, and the point is the near snow's own (SnowPoints), so far
// and near show the same columns white at the hand-over and build up and thaw alike. At and above
// the bare line there is snow whatever the shares; below the far snow line none. Under Almanac's
// regional weather the snow differs by place: past the near land the shares ease over 512 blocks
// from those where the player stands to each place's (FarSnowPlaces, in FarSnowColours' rows from
// 512), so near and far still agree at the hand-over.
//
// How it shows: upward faces with snow turn to snow, whiter as layers add up (a stair's steps and a
// slab's top too, the fake snow's top-face rule); leaves whole or only on top (FarSnowColourOrigin.z);
// grass and ferns standing in snow white (FarSnowColourOrigin.w), flowers buried to the snow's depth.
// Snow the far land was seen with that has thawed shows the season's grass colour in its place.
//
// Texels: one block column every 16 blocks for 512 x 512 texels around the camera (the inner ring,
// rows 0 to 511), then one every 64 blocks for the next 512 rows (the outer ring), as two unsigned
// 16-bit values: the bare line's code, and the far snow line's code with the column's climate in
// its top two bits (0 unknown, 1 HOT, 2 TEMPERATE, 3 COLD). A code is 0 unknown (left as seen),
// else the line's height above the level's floor plus 2, so the test for a surface is
// height + 2 >= code; everywhere is 1 and nowhere 16383.

layout(std140) uniform FarSnow {
    ivec4 FarSnowOrigins;      // xy the inner ring's first column (block x, z), zw the outer ring's
    ivec4 FarSnowColourOrigin; // xy the colours' first column (block x, z); z 1 leaves white only on top; w 1 grass in snow white
    vec4 FarSnowState;         // x where the snow by place starts (blocks from the camera; -1 none), y the level's floor (block y), z 1 when colours are known, w 1 for the debug view
    vec4 FarSnowShares[3];     // each climate's (HOT, TEMPERATE, COLD) layer shares where the player stands, layers 1 to 4
    ivec4 FarSnowPlaces;       // xy the first place's corner (block x, z), zw the camera's column
};

uniform sampler2D FarSnowLines;
uniform sampler2D FarSnowColours;

const int LITE_FAR_SNOW_SIZE = 512;
const int LITE_FAR_SNOW_INNER_SHIFT = 4;   // 16 blocks a texel
const int LITE_FAR_SNOW_OUTER_SHIFT = 6;   // 64 blocks a texel
const int LITE_FAR_SNOW_COLOUR_SHIFT = 5;  // 32 blocks a texel
// The snow by place (FarSnowPlaces): 64 x 64 places 512 blocks apart, a block of 64 texels per
// climate across, from row 512 of FarSnowColours, each layer's share as its step out of 32.
const int LITE_FAR_SNOW_PLACES = 64;
const float LITE_FAR_SNOW_PLACE_SIZE = 512.0;
const int LITE_FAR_SNOW_PLACES_ROW = 512;
const float LITE_FAR_SNOW_PLACES_EASE = 512.0;
const float LITE_FAR_SNOW_STEPS = 32.0;
const uint LITE_FAR_SNOW_CODE = 0x3FFFu;
// Snow's colour before lighting, as the near snowy leaves use it (weather/snow_cover.glsl), and how
// much of the surface's own texture still shows under one layer; from two layers none.
const vec3 LITE_FAR_SNOW_WHITE = vec3(0.9, 0.93, 0.97);
const float LITE_FAR_SNOW_ONE_LAYER = 0.9;
// Vanilla's grass top texture is grey, tinted by the biome's colour; this is its average.
const float LITE_FAR_SNOW_GRASS_GREY = 0.6;

// The near snow's column point (api SnowPoints.point), in the same 32-bit steps.
uint lite_far_snow_mix(uint value) {
    value ^= value >> 16;
    value *= 0x7FEB352Du;
    value ^= value >> 15;
    value *= 0x846CA68Bu;
    value ^= value >> 16;
    return value;
}

float lite_far_snow_point(ivec2 column) {
    uint hash = lite_far_snow_mix(uint(column.x) * 0x9E3779B9u ^ lite_far_snow_mix(uint(column.y) ^ 0x632BE5ABu));
    return float(hash >> 8) * (1.0 / 16777216.0);
}

// The two codes and the climate of a column, from the inner ring if it reaches, else the outer;
// false where neither does.
bool lite_far_snow_codes(ivec2 column, out uvec2 codes, out int climate) {
    ivec2 inner = (column - FarSnowOrigins.xy) >> LITE_FAR_SNOW_INNER_SHIFT;
    ivec2 texel;
    if (all(greaterThanEqual(inner, ivec2(0))) && all(lessThan(inner, ivec2(LITE_FAR_SNOW_SIZE)))) {
        texel = inner;
    } else {
        ivec2 outer = (column - FarSnowOrigins.zw) >> LITE_FAR_SNOW_OUTER_SHIFT;
        if (any(lessThan(outer, ivec2(0))) || any(greaterThanEqual(outer, ivec2(LITE_FAR_SNOW_SIZE)))) {
            return false;
        }
        texel = outer + ivec2(0, LITE_FAR_SNOW_SIZE);
    }
    uvec2 raw = uvec2(texelFetch(FarSnowLines, texel, 0).rg * 65535.0 + 0.5);
    codes = uvec2(raw.x, raw.y & LITE_FAR_SNOW_CODE);
    climate = int(raw.y >> 14);
    return codes.y != 0u;
}

// A climate's layer shares at a column: where the player stands near the camera, easing to the
// places' round the column (between their middles, by the colours' linear sampler) past the near land.
vec4 lite_far_snow_shares(ivec2 column, int climate) {
    vec4 here = FarSnowShares[climate - 1];
    if (FarSnowState.x < 0.0) {
        return here;
    }
    float ease = clamp((length(vec2(column - FarSnowPlaces.zw)) - FarSnowState.x) / LITE_FAR_SNOW_PLACES_EASE, 0.0, 1.0);
    if (ease <= 0.0) {
        return here;
    }
    // Clamped to the climate's own block of texels, so no other climate's places are blended in.
    vec2 place = clamp((vec2(column - FarSnowPlaces.xy) + 0.5) / LITE_FAR_SNOW_PLACE_SIZE, vec2(0.5), vec2(float(LITE_FAR_SNOW_PLACES) - 0.5));
    vec2 texel = vec2(float((climate - 1) * LITE_FAR_SNOW_PLACES), float(LITE_FAR_SNOW_PLACES_ROW)) + place;
    vec4 there = texture(FarSnowColours, texel / vec2(textureSize(FarSnowColours, 0))) * (255.0 / LITE_FAR_SNOW_STEPS);
    return mix(here, there, ease);
}

// Layers of snow on a surface at height surfaceY in a column: -1 where far snow doesn't know.
int lite_far_snow_layers(ivec2 column, float surfaceY) {
    uvec2 codes;
    int climate;
    if (!lite_far_snow_codes(column, codes, climate)) {
        return -1;
    }
    float height = surfaceY - FarSnowState.y + 2.0;
    int layers = 0;
    if (height >= float(codes.y) && climate > 0) {
        vec4 shares = lite_far_snow_shares(column, climate);
        float point = lite_far_snow_point(column);
        layers = int(point < shares.x) + int(point < shares.y) + int(point < shares.z) + int(point < shares.w);
    }
    if (codes.x != 0u && height >= float(codes.x)) {
        layers = max(layers, 1);
    }
    return layers;
}

// The season's grass colour at a column, or a negative colour where it isn't known.
vec3 lite_far_snow_grass(ivec2 column) {
    if (FarSnowState.z < 0.5) {
        return vec3(-1.0);
    }
    ivec2 texel = (column - FarSnowColourOrigin.xy) >> LITE_FAR_SNOW_COLOUR_SHIFT;
    if (any(lessThan(texel, ivec2(0))) || any(greaterThanEqual(texel, ivec2(256)))) {
        return vec3(-1.0);
    }
    return texelFetch(FarSnowColours, texel, 0).rgb;
}

// color: the far land's colour before lighting (sprite, tint and face shade). worldY is the
// fragment's height in the world, block the block its face belongs to; cards are plants' crossed cards.
void lite_far_land_snow(inout vec4 color, float worldY, ivec3 block, vec3 normal, int material, bool card, float shade) {
    ivec2 column = block.xz;
    bool leaves = material == LITE_MATERIAL_LEAVES;
    bool grass = material == LITE_MATERIAL_GRASS || material == LITE_MATERIAL_GRASS_UPPER;
    if (card) {
        if (!lite_material_is_foliage(material)) {
            return;
        }
        // A plant stands on the block under it (a tall plant's upper half two under).
        int below = material == LITE_MATERIAL_GRASS_UPPER || material == LITE_MATERIAL_PLANT_UPPER ? 1 : 0;
        int layers = lite_far_snow_layers(column, float(block.y - below));
        if (layers <= 0) {
            return;
        }
        float buried = float(layers) / 8.0;
        bool white = (grass && FarSnowColourOrigin.w != 0) || (worldY - float(block.y - below)) < buried;
        if (white) {
            color.rgb = mix(color.rgb, LITE_FAR_SNOW_WHITE * (0.82 + 0.18 * min(1.0, dot(color.rgb, vec3(0.6)))), 0.95);
        }
        return;
    }
    // Snow never lies on ice, as vanilla's can't.
    if (material == LITE_MATERIAL_ICE) {
        return;
    }
    bool top = normal.y > 0.5;
    if (!top && !(leaves && FarSnowColourOrigin.z == 0 && normal.y > -0.5)) {
        return;
    }
    // A top face's surface is its own height; a leaf block's sides take the snow on its top.
    float surfaceY = top ? worldY : float(block.y + 1);
    int layers = lite_far_snow_layers(column, surfaceY);
    if (layers < 0) {
        return;
    }
    if (layers == 0) {
        // Snow the far land was seen with that has since thawed: the season's grass in its place.
        if (top && material == LITE_MATERIAL_SNOW) {
            vec3 grassColour = lite_far_snow_grass(column);
            if (grassColour.r >= 0.0) {
                color.rgb = grassColour * LITE_FAR_SNOW_GRASS_GREY * shade;
            }
        }
        return;
    }
    float amount = layers >= 2 ? 1.0 : LITE_FAR_SNOW_ONE_LAYER;
    color.rgb = mix(color.rgb, LITE_FAR_SNOW_WHITE * shade, amount);
}

#endif
