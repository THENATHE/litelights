#ifndef LITELIGHTS_WEATHER_SNOW_COVER_GLSL
#define LITELIGHTS_WEATHER_SNOW_COVER_GLSL

#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:water/uniforms.glsl>
#include <litelights:water/common.glsl>

// Snowy leaves and ground (Weather details, snowleaves099). In biomes where it snows, leaves open
// to the sky frost over while it snows, the leaf texture's brighter texels first, the whole block
// alike (1.0.7: its sides and underside with its top, where the snow used to creep down the sides
// from the top edge), as snow caught in a canopy; and the bare ground under the trees, which
// vanilla's snow skips, takes a thin, patchy dusting. Grass, ferns and flowers standing among snow
// layers turn white too, as snowy grass (1.0.7), whether or not it is snowing. Called on the block
// texture's colour before lighting, so the snow is lit, shadowed and fogged like the block it lies on.
//
// Values from com.thenathe.litelights.render.feature.weather.SnowyLeaves (the LiteFrame block):
//   LiteSnowCover  x how much snow has gathered (0: none), y 1 while the settings screen previews
//                  snow, which then shows on canopies in any biome, z 1 while plants standing in
//                  snow are whitened (the option is on, visual snow off), w the visual snow's parts
//                  (bits LITE_SNOW_VISUAL_*, SnowyLeaves.visualBits)
// and from the water stream's column map, the snow cover region (SnowCoverGrid,
// FluidDataTexture.SNOW_COVER_Y): which columns are topped by leaves with nothing but sky over
// them, whether their biome snows, the canopy's and the bare ground's heights. The CPU has already
// left out canopies under a roof and ground that isn't bare.

const int LITE_SNOW_COVER_Y = 1408;
const int LITE_SNOW_CANOPY = 1;
const int LITE_SNOW_SNOWS = 2;
const int LITE_SNOW_CONIFER = 4;
const int LITE_SNOW_GROUND = 8;
// The block on the column's ground is a plant with snow layers beside it (SnowCoverGrid.PLANT_IN_SNOW).
const int LITE_SNOW_PLANT = 64;
// A plant in snow is this white: a little of its own colour shows, as on vanilla's snowy grass.
const float LITE_SNOW_PLANT_WHITE = 0.88;
// Under an overhang, bare ground the snow outside fans in onto (SnowCoverGrid.OVERHANG).
const int LITE_SNOW_OVERHANG = 128;
// The visual snow's parts, LiteSnowCover.w (SnowyLeaves.VISUAL_*): on at all (plants then whiten
// by the grass bit alone, grass and ferns only, and fully), white grass, leaves white only on top,
// the dusting under trees fanning in from the snow around, snow fanning in under overhangs.
const int LITE_SNOW_VISUAL_ON = 1;
const int LITE_SNOW_VISUAL_GRASS = 2;
const int LITE_SNOW_VISUAL_LEAF_TOPS = 4;
const int LITE_SNOW_VISUAL_FAN_TREES = 8;
const int LITE_SNOW_VISUAL_FAN_OVERHANGS = 16;
// Grass and ferns in snow with the visual snow on: completely white, the blades' light and dark kept.
const float LITE_SNOW_GRASS_WHITE = 0.96;
// The fanned dusting: how soft each texel's edge is against the share of snow that reaches it.
const float LITE_SNOW_FAN_SOFT = 0.06;
// How much of the texels the fanned dusting turns white for the share of snow that reaches them:
// a little under three quarters since visual-snow-117, so the ground shows through more (the owner).
const float LITE_SNOW_FAN_COVER = 0.72;
// A face needs at least this much sky light (16 a level): the ground under the thickest canopy
// keeps about 11. It rules out most of what lies under the canopy before the texel fetch.
const float LITE_SNOW_SKY_LIGHT = 112.0;
// How much of the snow a leaf block takes by how far it is under its column's top leaf block: the
// top one all of it, the ones under it less, none four or more blocks down.
const float LITE_SNOW_DEPTH[4] = float[4](1.0, 0.7, 0.4, 0.2);
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
// The softness never reaches below no snow at all, so a texel is bare while there is none: as the
// snow starts, and at the snow map's edge, where the amount fades to none.
float lite_snow_texel(float need, float amount, float distance) {
    float soft = min(mix(0.02, 0.3, smoothstep(LITE_SNOW_SOFT_START, LITE_SNOW_SOFT_END, distance)), need);
    return smoothstep(need - soft, need + soft, amount);
}

// Whether the plant block at a spot stands in snow: the column's flag, and the plant's own block or
// the upper half of a tall one.
bool lite_snow_plant_at(ivec3 block) {
    if (!lite_fluid_map_contains(block.xz)) {
        return false;
    }
    ivec4 texel = ivec4(texelFetch(LiteFluidData, (block.xz & 255) + ivec2(0, LITE_SNOW_COVER_Y), 0) * 255.0 + 0.5);
    int above = (block.y - texel.b) & 255;
    return (texel.r & LITE_SNOW_PLANT) != 0 && (above == 1 || above == 2);
}

// Grass, ferns and flowers standing among snow layers, white as snowy grass. A plant's model can be
// shifted up to a quarter block off its block, so a face near a block's edge also asks the block
// beside it.
void lite_snow_plant(inout vec4 color, vec4 vertexColor, vec3 position, float whiteness) {
    vec3 inside = lite_block_local(position);
    ivec3 block = LiteCameraBlock.xyz + ivec3(floor(inside));
    vec2 within = fract(inside.xz);
    ivec2 beside = ivec2(within.x < 0.26 ? -1 : within.x > 0.74 ? 1 : 0, within.y < 0.26 ? -1 : within.y > 0.74 ? 1 : 0);
    bool snowy = lite_snow_plant_at(block);
    if (!snowy && beside.x != 0) {
        snowy = lite_snow_plant_at(block + ivec3(beside.x, 0, 0));
    }
    if (!snowy && beside.y != 0) {
        snowy = lite_snow_plant_at(block + ivec3(0, 0, beside.y));
    }
    if (!snowy) {
        return;
    }
    // The texture's own light and dark, so the blades still read as blades.
    vec3 albedo = color.rgb / max(vertexColor.rgb, vec3(0.02));
    float bright = clamp((max(albedo.r, max(albedo.g, albedo.b)) - 0.25) / 0.6, 0.0, 1.0);
    vec3 white = LITE_SNOW_WHITE * lite_snow_shading(vertexColor.rgb) * (0.82 + 0.18 * bright);
    color.rgb = mix(color.rgb, white, whiteness);
}

// The share of the snow that reaches a spot of fanned ground: the columns' fan amounts (A) blended
// between column centres, so the falloff is smooth across blocks; a column beside whose ground is
// at another height keeps the spot's own amount.
float lite_snow_fan(ivec3 block, vec3 inside, int ownFan) {
    vec2 spot = inside.xz - 0.5;
    ivec2 corner = ivec2(floor(spot)) + LiteCameraBlock.xz;
    vec2 blend = fract(spot);
    float own = float(ownFan) / 250.0;
    float samples[4];
    for (int i = 0; i < 4; i++) {
        ivec2 column = corner + ivec2(i & 1, i >> 1);
        samples[i] = own;
        if (lite_fluid_map_contains(column)) {
            ivec4 texel = ivec4(texelFetch(LiteFluidData, (column & 255) + ivec2(0, LITE_SNOW_COVER_Y), 0) * 255.0 + 0.5);
            if (texel.a > 0 && abs(((texel.b - block.y + 128) & 255) - 128) <= 1) {
                samples[i] = float(texel.a) / 250.0;
            }
        }
    }
    return mix(mix(samples[0], samples[1], blend.x), mix(samples[2], samples[3], blend.x), blend.y);
}

// A dusting fanned in from the snow beside: each texel turns white once enough of the snow reaches
// it, its own fixed share of the way in, so the share of white texels falls off steadily inward
// from the edge, as a thinning of crisp snow rather than patches or a grey wash.
void lite_snow_fanned(inout vec4 color, vec4 vertexColor, vec3 inside, ivec3 block, int ownFan) {
    float reach = lite_snow_fan(block, inside, ownFan);
    if (reach <= 0.0) {
        return;
    }
    ivec3 face = ivec3(floor(inside * 16.0)) + ivec3(LiteWaterAnchor.x, LiteCameraBlock.y, LiteWaterAnchor.y) * 16;
    float speckle = lite_water_hash(ivec2(face.x + face.y * 7, face.z - face.y * 13));
    float need = 0.04 + 0.92 * speckle;
    float snow = smoothstep(need - LITE_SNOW_FAN_SOFT, need + LITE_SNOW_FAN_SOFT, reach * LITE_SNOW_FAN_COVER) * LITE_SNOW_DUSTING;
    vec3 white = LITE_SNOW_WHITE * lite_snow_shading(vertexColor.rgb) * (0.94 + 0.06 * speckle);
    color.rgb = mix(color.rgb, white, snow);
}

// color: the block texture times the vertex colour, before lighting.
void lite_snow_cover_surface(inout vec4 color, vec4 vertexColor, vec3 position, vec3 normal, vec2 lightCoord, int material) {
    if (!lite_world_pass() || !lite_water(LITE_WATER_SNOW_COVER)) {
        return;
    }
    bool leaves = material == LITE_MATERIAL_LEAVES;
    int visual = int(LiteSnowCover.w + 0.5);
    if (!leaves && lite_material_is_foliage(material)) {
        if ((visual & LITE_SNOW_VISUAL_ON) != 0) {
            bool grass = material == LITE_MATERIAL_GRASS || material == LITE_MATERIAL_GRASS_UPPER;
            if (grass && (visual & LITE_SNOW_VISUAL_GRASS) != 0) {
                lite_snow_plant(color, vertexColor, position, LITE_SNOW_GRASS_WHITE);
            }
        } else if (LiteSnowCover.z > 0.5) {
            lite_snow_plant(color, vertexColor, position, LITE_SNOW_PLANT_WHITE);
        }
        return;
    }
    bool fanning = (visual & (LITE_SNOW_VISUAL_FAN_TREES | LITE_SNOW_VISUAL_FAN_OVERHANGS)) != 0;
    if ((LiteSnowCover.x <= 0.0 && !fanning) || (!leaves && normal.y < -0.5)) {
        return;
    }
    if (!leaves && normal.y < 0.9) {
        return;
    }
    // Under an overhang the sky light is lower than under a canopy; the fanning asks the map instead.
    if (lightCoord.y < LITE_SNOW_SKY_LIGHT && !(fanning && !leaves)) {
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
    if ((flags & LITE_SNOW_OVERHANG) != 0) {
        // Bare ground under a roof: the snow outside fans in, the same at any time of the snow's clock.
        if (leaves || (visual & LITE_SNOW_VISUAL_FAN_OVERHANGS) == 0 || (block.y & 255) != texel.b) {
            return;
        }
        lite_snow_fanned(color, vertexColor, inside, block, texel.a);
        return;
    }
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
        if (LiteSnowCover.x <= 0.0 || lightCoord.y < LITE_SNOW_SKY_LIGHT) {
            return;
        }
        int depth = (texel.g - block.y) & 255;
        if (depth > 3) {
            return;
        }
        amount *= LITE_SNOW_DEPTH[depth] * ((flags & LITE_SNOW_CONIFER) != 0 ? LITE_SNOW_CONIFER_HOLDS : LITE_SNOW_BROADLEAF_HOLDS);
        // The leaf texture's own brightness: snow settles on the leaves, not in the gaps between.
        vec3 albedo = color.rgb / max(vertexColor.rgb, vec3(0.02));
        float bright = clamp((max(albedo.r, max(albedo.g, albedo.b)) - 0.25) / 0.6, 0.0, 1.0);
        float need = 0.12 + 0.55 * (1.0 - bright) + 0.3 * speckle;
        if ((visual & LITE_SNOW_VISUAL_LEAF_TOPS) != 0 && normal.y < 0.5) {
            // Tops only: no snow under the block, and down its sides only from the top edge, further
            // as more falls, as before 1.0.7.
            float fromTop = 1.0 - fract(inside.y);
            if (normal.y < -0.5 || fromTop > 0.45 * amount) {
                return;
            }
            need += 0.25 * fromTop / max(0.45 * amount, 0.01);
        }
        // Whole: the whole block alike, its sides and underside whitening with its top.
        snow = lite_snow_texel(need, amount * 1.3, distance) * (0.75 + 0.25 * bright);
    } else {
        if ((flags & LITE_SNOW_GROUND) == 0 || (block.y & 255) != texel.b) {
            return;
        }
        if ((visual & LITE_SNOW_VISUAL_FAN_TREES) != 0) {
            // Even snow under trees: fanning in from the snow around the canopy, not the old patches.
            lite_snow_fanned(color, vertexColor, inside, block, texel.a);
            return;
        }
        if (LiteSnowCover.x <= 0.0 || lightCoord.y < LITE_SNOW_SKY_LIGHT) {
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
