#ifndef LITELIGHTS_WEATHER_PUDDLES_GLSL
#define LITELIGHTS_WEATHER_PUDDLES_GLSL

#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:surface.glsl>
#include <litelights:water/uniforms.glsl>
#include <litelights:water/common.glsl>
#include <litelights:water/reflection.glsl>

// Puddles in the rain (Weather details, rain099). Thin sheets of water on the tops of the blocks
// rain falls on, in low patches of a fixed world-space noise, so they stay put, have organic
// outlines, grow out from their lowest spots as it rains and dry back into them afterwards. Their
// look is the ocean swash's thin water (water/waves.glsl, lite_ocean_swash): the ground darkened
// and seen through a little of the water's own colour under the chosen Water colours, catching
// the sky, on the 16-per-block texel grid like the rest of the pixel art.
//
// Values from com.thenathe.litelights.render.feature.weather.Puddles (the LiteFrame block):
//   LitePuddles  x how far they have grown (0: none, one uniform test), y rain falling on them,
//                z the biome's water tint near the camera, packed 0xRRGGBB
// and from the water stream's column map, the rain tops (RainTopGrid): a face holds a puddle only
// if it is the top of the highest block rain stops on in its column, so none forms under a roof,
// an overhang or a tree's leaves, and none under water; and only on level ground, where none of the
// eight columns around it is lower (lite_puddle_level_ground).

// The noise the puddles lie in: low where it is small. Its cells are 4, 2 and 1 blocks, and each
// octave's lattice wraps with the 2048-block anchor (see lite_water_anchored).
float lite_puddle_octave(vec2 p, int period) {
    ivec2 cell = ivec2(floor(p));
    vec2 f = fract(p);
    vec2 s = f * f * (3.0 - 2.0 * f);
    int wrap = period - 1;
    float a = lite_water_hash(cell & wrap);
    float b = lite_water_hash((cell + ivec2(1, 0)) & wrap);
    float c = lite_water_hash((cell + ivec2(0, 1)) & wrap);
    float d = lite_water_hash((cell + ivec2(1, 1)) & wrap);
    return mix(mix(a, b, s.x), mix(c, d, s.x), s.y);
}

float lite_puddle_ground(vec2 anchored) {
    return 0.6 * lite_puddle_octave(anchored * 0.25, 512) + 0.28 * lite_puddle_octave(anchored * 0.5, 1024)
        + 0.12 * lite_puddle_octave(anchored + vec2(37.0, 71.0), 1024);
}

// The ground below which water lies, from how far the puddles have grown: about 4 % of open
// ground holds water at 0.4 (a minute of rain), 18 % at 1 and 25 % at 1.3 (the noise's lowest
// shares, measured over its distribution). Growing and drying move this line, so the puddles
// spread from their lowest spots and shrink back into them, edges first.
float lite_puddle_level(float cover) {
    return 0.12 + 0.235 * pow(cover, 0.7);
}

// Blocks whose tops can hold a puddle: plain ground and building blocks (grass, dirt, paths, stone,
// gravel, bricks, planks and the like) and metal. Not sand, snow, farmland, leaves, plants, glass,
// ice, fluids or anything that glows.
bool lite_puddle_material(int material) {
    return material == LITE_MATERIAL_DEFAULT || material == LITE_MATERIAL_METAL;
}

// Rain reaches this face: it is the top of the highest block in its column that rain stops on, and
// at least half a block above that block's floor (slabs, paths, full blocks; not carpets or snow
// layers). worldY is the face's height in the world.
bool lite_puddle_open_to_rain(ivec2 column, float worldY) {
    if (!lite_fluid_map_contains(column)) {
        return false;
    }
    ivec4 texel = ivec4(texelFetch(LiteFluidData, (column & 255) + ivec2(0, 256), 0) * 255.0 + 0.5);
    int packed = texel.r + texel.g * 256;
    if (packed == 0) {
        return false;
    }
    // The map holds the height of that block itself (the heightmap's first free height, less one).
    int rainTop = packed - 1 + LiteWaterFlags.w;
    int block = int(floor(worldY - 0.02));
    return block == rainTop && worldY - float(block) > 0.49;
}

// The height of the highest block rain stops on in a column, or a very high value where the map
// doesn't say (outside it, or nothing there to stop the rain).
int lite_puddle_rain_top(ivec2 column) {
    if (!lite_fluid_map_contains(column)) {
        return 1000000;
    }
    ivec4 texel = ivec4(texelFetch(LiteFluidData, (column & 255) + ivec2(0, 256), 0) * 255.0 + 0.5);
    int packed = texel.r + texel.g * 256;
    return packed == 0 ? 1000000 : packed - 1 + LiteWaterFlags.w;
}

// Water only pools on level ground: a block with lower ground beside it, on any of its eight sides,
// would drain, so the edge of a step, a hillside's terraces, a pillar's top or a roof's eaves hold
// none. Higher ground beside it is fine (the foot of a wall, the bottom of a hollow).
bool lite_puddle_level_ground(ivec2 column, float worldY) {
    int own = int(floor(worldY - 0.02));
    for (int dz = -1; dz <= 1; dz++) {
        for (int dx = -1; dx <= 1; dx++) {
            if ((dx != 0 || dz != 0) && lite_puddle_rain_top(column + ivec2(dx, dz)) < own) {
                return false;
            }
        }
    }
    return true;
}

// The water's colour for the sheet, as the water itself is drawn under each Water colours palette
// (lite_water_body, for the water texture's average texel), from the biome's water tint near the
// camera. The same rules as the swash's sheet (lite_swash_water); hue is the water's hue, for the
// ground seen through it.
vec3 lite_puddle_water(vec3 light, out vec3 hue) {
    int packed = int(LitePuddles.z + 0.5);
    vec3 tint = vec3(float((packed >> 16) & 255), float((packed >> 8) & 255), float(packed & 255)) / 255.0;
    hue = tint;
    if (lite_water(LITE_WATER_REALISTIC) || lite_water(LITE_WATER_ENHANCED)) {
        hue = lite_water(LITE_WATER_BIOME_DEPTH) ? mix(tint, LITE_REALISTIC_WATER, 0.3) : LITE_REALISTIC_WATER;
        return hue * light * 0.6;
    }
    return LiteWaterSpriteMeans[0].rgb * tint * light;
}

// A 4 x 4 ordered dither on the texel grid (as the waves'), so the sheet's soft edge turns into whole texels.
float lite_puddle_dither(ivec2 texel) {
    const int BAYER[16] = int[16](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
    return (float(BAYER[(texel.x & 3) + (texel.y & 3) * 4]) + 0.5) / 16.0;
}

// 1 where a 1/16 block texel covers a pixel or more, 0 where three or more texels share one, from
// the screen derivatives of the face's position: the texel-grid details give way to their average there.
float lite_puddle_fine(vec3 dpdx, vec3 dpdy) {
    float blocksPerPixel = max(length(dpdx), length(dpdy));
    return 1.0 - smoothstep(0.6, 1.6, blocksPerPixel * 16.0);
}

// Puddles fade out over the column map's last dozen columns instead of stopping in a straight line.
float lite_puddle_map_fade(ivec2 column) {
    ivec2 inMap = column - LiteWaterFlags.yz;
    int toEdge = min(min(inMap.x, inMap.y), min(255 - inMap.x, 255 - inMap.y));
    return smoothstep(0.0, 12.0, float(toEdge));
}

// Rings where raindrops land on the water: each half-block cell has a drop now and then, at a
// hashed spot and moment near its middle, whose ring spreads three texels, staying inside the cell,
// and fades. 1 on a ring texel.
float lite_puddle_ring(ivec2 texel, float rain, float time) {
    const float CYCLE = 0.8;
    ivec2 cell = texel >> 3;
    float offset = lite_water_hash(cell + ivec2(311, 97)) * CYCLE;
    float cycles = (time + offset) / CYCLE;
    int drop = int(floor(cycles));
    ivec2 dropCell = cell + ivec2(drop * 37, drop * 113);
    if (lite_water_hash(dropCell) > rain * 0.45) {
        return 0.0;
    }
    float age = fract(cycles);
    vec2 centre = vec2(cell << 3) + 3.5 + lite_water_hash2(dropCell + ivec2(53, 19));
    float radius = 0.4 + age * 2.7;
    float distance = length(vec2(texel) + 0.5 - centre);
    return step(abs(distance - radius), 0.5) * (1.0 - age);
}

// Lays a puddle over a shaded terrain fragment, if it holds one. color is lit, before fog.
void lite_puddle_surface(inout vec4 color, LiteSurface surface, vec3 dpdx, vec3 dpdy) {
    if (surface.normal.y < 0.9 || !lite_puddle_material(surface.material) || surface.lightCoord.y < 13.5 * 16.0
        || lite_feature(LITE_FEATURE_CAMERA_UNDERWATER)) {
        return;
    }
    vec3 local = lite_block_local(surface.position);
    float worldY = float(LiteCameraBlock.y) + local.y;
    ivec2 column = lite_water_world_column(surface.position - vec3(0.0, 0.02, 0.0));
    if (!lite_puddle_open_to_rain(column, worldY)) {
        return;
    }

    // Everything below is worked out at the centre of the fragment's texel.
    vec2 anchored = lite_water_anchored(surface.position);
    ivec2 texel = ivec2(floor(anchored * 16.0));
    vec2 centre = (vec2(texel) + 0.5) / 16.0;
    float depth = lite_puddle_level(LitePuddles.x) - lite_puddle_ground(centre);
    if (depth < -0.03 || !lite_puddle_level_ground(column, worldY)) {
        return;
    }
    float fine = lite_puddle_fine(dpdx, dpdy);
    float dither = mix(0.5, lite_puddle_dither(texel), fine);
    float fade = lite_puddle_map_fade(column);

    // How bright the light here is, from the shaded colour over the texture.
    vec3 albedo = textureLod(LiteWaterAtlas, surface.uv, 0.0).rgb;
    float light = max(color.r, max(color.g, color.b)) / max(max(albedo.r, max(albedo.g, albedo.b)), 0.04);

    // Damp ground around the water's edge, darker under it; the sheet thickens from its edge in.
    float wet = depth >= 0.0 ? 1.0 : 0.5 * step(dither, 1.0 + depth / 0.03);
    vec3 ground = color.rgb * (1.0 - 0.25 * wet * fade);
    float sheet = clamp(depth / 0.05, 0.0, 1.0);
    sheet = mix(sheet, floor(sheet * 3.0 + dither) / 3.0, fine) * step(0.0, depth) * fade;
    if (sheet <= 0.0) {
        color.rgb = ground;
        return;
    }

    // The swash's sheet: the ground seen through a little of the water, which a sheet a few pixels
    // deep shows brighter than a body of water seen from above.
    vec3 hue;
    vec3 water = lite_puddle_water(vec3(light), hue) * 1.8;
    vec3 filtered = mix(vec3(1.0), hue / max(max(hue.r, hue.g), max(hue.b, 0.05)), 0.45) * 0.95;
    vec3 thinWater = mix(ground * filtered, water + LiteWaterSkyColor.rgb * 0.2 * light, 0.4);
    vec3 seen = mix(ground, thinWater, 0.6 * sheet);

    // A light mirror: the sky and the sun, shaded where the ground is, strongest at a low angle.
    vec3 view = normalize(surface.position - lite_view_origin());
    float fresnel = 0.03 + 0.97 * pow(1.0 - clamp(-view.y, 0.0, 1.0), 5.0);
    vec3 direction = normalize(vec3(view.x, max(-view.y, 0.02), view.z));
    vec3 sky = lite_water_shaded_sky(direction, lite_mirror_shade(surface.shadow, surface.facing))
        * lite_water_sky_visibility(surface.lightCoord);
    seen = mix(seen, sky, fresnel * 0.55 * sheet);

    if (LitePuddles.y > 0.0 && fine > 0.0) {
        float ring = lite_puddle_ring(texel, LitePuddles.y, LiteTime.y) * fine * step(0.34, sheet);
        // A ring catches the sky on its little crest: lighter than the water around it in any light.
        seen = mix(seen, seen * 1.5 + LiteWaterSkyColor.rgb * light * 0.4 + 0.04 * light, ring * 0.85);
    }
    color.rgb = seen;
}

#endif
