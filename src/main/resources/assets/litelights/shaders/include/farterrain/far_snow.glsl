#ifndef LITELIGHTS_FARTERRAIN_FAR_SNOW_GLSL
#define LITELIGHTS_FARTERRAIN_FAR_SNOW_GLSL

// Seasonal snow on far terrain (farsnow; render.feature.farterrain.FarSnow). Only in the far pass's
// LITE_FAR_SNOW variant, which runs only with Almanac on the client: without it the far pass is
// the same shader as before.
//
// Voxy and Distant Horizons draw far land as it was when each chunk was last loaded, snow and all.
// Almanac says, per block column, the lowest height where snow lies now (its far snow line), and
// FarSnow keeps that in FarSnowLines around the camera. Here, on far pixels that face up, the
// captured look is brought in line with it: snow is painted where it belongs and the capture has
// none, and captured snow is turned back to the season's grass where none belongs.
//
// When the line changes (a new sub-season, the first snowfall of a winter), FarSnowLines holds the
// line before (r) and after (g) the change, and FarSnowState.x runs from 0 to 1 over the change.
// A change already under way when the player joined (Almanac 0.1.3's change tick) has no "before"
// lines: the far land as the far mod captured it stands in for them.
// Every block column has its own fixed threshold (a hash of the column mixed with patch noise), and
// a pixel whose before and after disagree switches once the progress passes it: snow comes and goes
// in block-shaped patches that spread and join, never as a cross-fade. Where before and after agree
// (peaks, snow that stays) nothing moves.
//
// Texels: one block column every 16 blocks for 512 x 512 texels around the camera (the inner ring,
// rows 0 to 511), then one every 64 blocks for the next 512 rows (the outer ring), as unsigned
// 16-bit codes: 0 unknown (left as captured), else the line's height above the level's floor plus
// 2, so the test for a surface is height + 2 >= code; everywhere is 1 and nowhere 65535.
//
// The far mods also keep the grass and leaf colours of the day they captured a chunk, so grass and
// leaves are tinted toward the season's colours everywhere (FarSnowColours: grass in rows 0 to 255,
// foliage in rows 256 to 511, one texel every 32 blocks; the foliage's alpha is 0 where colours
// aren't known or the land is hot all year, whose sand and terracotta stay as they are, 255 where
// the texel and its neighbours in x, z and both have the same colours, else 254).

layout(std140) uniform FarSnow {
    ivec4 FarSnowOrigins;      // xy the inner ring's first column (block x, z), zw the outer ring's
    ivec4 FarSnowColourOrigin; // xy the colours' first column (block x, z); z 1 while a change resumed on joining is shown; w unused
    vec4 FarSnowState;         // x the change's progress 0..1 (1 when nothing is changing), y the level's floor (block y), z 1 when colours are known, w 1 for the debug view
};

uniform sampler2D FarSnowLines;
uniform sampler2D FarSnowColours;

const int LITE_FAR_SNOW_SIZE = 512;
const int LITE_FAR_SNOW_INNER_SHIFT = 4;   // 16 blocks a texel
const int LITE_FAR_SNOW_OUTER_SHIFT = 6;   // 64 blocks a texel
const int LITE_FAR_SNOW_COLOUR_SIZE = 256;
const int LITE_FAR_SNOW_COLOUR_SHIFT = 5;  // 32 blocks a texel
// Snow's colour before lighting, as the snowy leaves use it (weather/snow_cover.glsl).
const vec3 LITE_FAR_SNOW_WHITE = vec3(0.9, 0.93, 0.97);
// Captured snow: a top face whose colour before lighting is this bright and this grey.
const float LITE_FAR_SNOW_CAPTURED_LEAST = 0.62;
const float LITE_FAR_SNOW_CAPTURED_SPREAD = 0.12;
// Vanilla's grass top texture is grey, tinted by the biome's colour; this is its average.
const float LITE_FAR_SNOW_GRASS_GREY = 0.6;
// Snow is painted on surfaces at least this flat (the normal's height), and captured snow taken
// off surfaces at least this flat.
const float LITE_FAR_SNOW_FLATTEST = 0.7;
const float LITE_FAR_SNOW_STEEPEST = 0.35;
// Where water is found, how much bluer than red and green a colour must be to look like water.
const float LITE_FAR_SNOW_WATER_BLUE = 0.05;
// Plants the season tints, by hue (degrees) and saturation before lighting: from fall's orange
// leaves through olive to green grass and leaves, with spruce's and taiga's blue-green (from about
// 110) left out. How saturated a colour must be depends on its hue: green from 0.22; yellow from
// 0.42, above sand and sandstone (about 0.3); orange and brown from 0.6, above dirt, coarse dirt
// and wood (about 0.5).
const vec4 LITE_FAR_TINT_HUES = vec4(12.0, 18.0, 110.0, 118.0);
const vec3 LITE_FAR_TINT_LEAST_SATURATION = vec3(0.6, 0.42, 0.22); // orange, yellow, green
const vec4 LITE_FAR_TINT_SATURATION_HUES = vec4(40.0, 50.0, 58.0, 66.0); // orange to yellow, yellow to green
// How far the snow line is roughened, in blocks, so it never runs as a straight contour.
const float LITE_FAR_SNOW_LINE_JITTER = 3.0;

// A fixed 32-bit hash of a block column.
uint lite_far_snow_hash(ivec2 column) {
    uint h = uint(column.x) * 0x8DA6B343u ^ uint(column.y) * 0xD8163841u;
    h ^= h >> 15;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12;
    h *= 0x297A2D39u;
    h ^= h >> 15;
    return h;
}

float lite_far_snow_unit(uint h) {
    return float(h & 0xFFFFu) / 65535.0;
}

// Smooth noise over 32-block cells, read at a block column's corner: constant across each column,
// so the patches it makes have block edges.
float lite_far_snow_patches(ivec2 column) {
    ivec2 cell = column >> 5;
    vec2 within = vec2(column & 31) / 32.0;
    vec2 blend = within * within * (3.0 - 2.0 * within);
    float a = lite_far_snow_unit(lite_far_snow_hash(cell));
    float b = lite_far_snow_unit(lite_far_snow_hash(cell + ivec2(1, 0)));
    float c = lite_far_snow_unit(lite_far_snow_hash(cell + ivec2(0, 1)));
    float d = lite_far_snow_unit(lite_far_snow_hash(cell + ivec2(1, 1)));
    return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

// The before (x) and after (y) codes for a column, or 0 outside both rings.
vec2 lite_far_snow_codes(ivec2 column) {
    ivec2 inner = (column - FarSnowOrigins.xy) >> LITE_FAR_SNOW_INNER_SHIFT;
    ivec2 texel;
    if (all(greaterThanEqual(inner, ivec2(0))) && all(lessThan(inner, ivec2(LITE_FAR_SNOW_SIZE)))) {
        texel = inner;
    } else {
        ivec2 outer = (column - FarSnowOrigins.zw) >> LITE_FAR_SNOW_OUTER_SHIFT;
        if (any(lessThan(outer, ivec2(0))) || any(greaterThanEqual(outer, ivec2(LITE_FAR_SNOW_SIZE)))) {
            return vec2(0.0);
        }
        texel = outer + ivec2(0, LITE_FAR_SNOW_SIZE);
    }
    return floor(texelFetch(FarSnowLines, texel, 0).rg * 65535.0 + 0.5);
}

// Where a column's colours are, in texels of the grass rows (texel i holds the middle of its
// 32-block cell, so neighbours blend), or x < 0 outside them.
vec2 lite_far_snow_colour_texel(ivec2 column) {
    vec2 at = vec2(column - FarSnowColourOrigin.xy) / float(1 << LITE_FAR_SNOW_COLOUR_SHIFT) - 0.5;
    if (FarSnowState.z < 0.5 || any(lessThan(at, vec2(0.0))) || any(greaterThan(at, vec2(float(LITE_FAR_SNOW_COLOUR_SIZE - 1))))) {
        return vec2(-1.0);
    }
    return at;
}

// The season's grass colour at a column, blended between cells, a = 0 where it isn't known.
vec4 lite_far_snow_grass(ivec2 column) {
    vec2 at = lite_far_snow_colour_texel(column);
    vec2 size = vec2(LITE_FAR_SNOW_COLOUR_SIZE, 2 * LITE_FAR_SNOW_COLOUR_SIZE);
    return at.x < 0.0 ? vec4(0.0) : texture(FarSnowColours, (at + 0.5) / size);
}

float lite_far_saturation(vec3 colour) {
    float most = max(colour.r, max(colour.g, colour.b));
    return most > 1.0e-3 ? (most - min(colour.r, min(colour.g, colour.b))) / most : 0.0;
}

float lite_far_hue(vec3 colour) {
    float most = max(colour.r, max(colour.g, colour.b));
    float spread = most - min(colour.r, min(colour.g, colour.b));
    if (spread < 1.0e-4) {
        return 0.0;
    }
    if (most == colour.r) {
        return 60.0 * mod((colour.g - colour.b) / spread, 6.0);
    }
    if (most == colour.g) {
        return 60.0 * ((colour.b - colour.r) / spread + 2.0);
    }
    return 60.0 * ((colour.r - colour.g) / spread + 4.0);
}

float lite_far_window(float value, vec4 edges) {
    return smoothstep(edges.x, edges.y, value) * (1.0 - smoothstep(edges.z, edges.w, value));
}

// How far apart two hues are, in degrees, around the circle.
float lite_far_hue_gap(float a, float b) {
    float gap = abs(a - b);
    return min(gap, 360.0 - gap);
}

// What a cell's colours make of a pixel: its foliage for leaves, its grass for grass. Leaves are
// tinted more strongly than grass in every biome and season, so the more saturated of the two is
// leaves; fall's orange and brown (fall 1) and faces too steep for snow are always leaves.
vec3 lite_far_cell_target(vec4 grass, vec4 foliage, float saturation, float fall, bool side) {
    float between = 0.5 * (lite_far_saturation(grass.rgb) + lite_far_saturation(foliage.rgb));
    float leaves = side ? 1.0 : max(smoothstep(between - 0.06, between + 0.06, saturation), fall);
    return mix(grass.rgb, foliage.rgb, leaves);
}

// Captured grass and leaves (albedo, before lighting) tinted toward the season's colours at the
// column, keeping their brightness, so the far land doesn't keep the colours of the day it was
// captured. side is true on faces too steep for snow: those are leaves (a grass block's sides are
// dirt).
//
// The colours are one per 32-block cell. Inside one biome the four cells around a pixel agree
// (FarSnow marks those with foliage alpha 255) and one cell's colours are used as they are. Near a
// biome border a pixel lies between cells of two biomes (an orange dappled forest beside green
// plains): of the four, those whose colour is nearer the pixel's own hue count for more, so each
// side of the border keeps its own biome's colour rather than a blend of both.
vec3 lite_far_season_tint(vec3 albedo, ivec2 column, bool side) {
    vec2 at = lite_far_snow_colour_texel(column);
    if (at.x < 0.0) {
        return albedo;
    }
    float hue = lite_far_hue(albedo);
    float saturation = lite_far_saturation(albedo);
    float least = mix(mix(LITE_FAR_TINT_LEAST_SATURATION.x, LITE_FAR_TINT_LEAST_SATURATION.y,
        smoothstep(LITE_FAR_TINT_SATURATION_HUES.x, LITE_FAR_TINT_SATURATION_HUES.y, hue)),
        LITE_FAR_TINT_LEAST_SATURATION.z, smoothstep(LITE_FAR_TINT_SATURATION_HUES.z, LITE_FAR_TINT_SATURATION_HUES.w, hue));
    float plant = lite_far_window(hue, LITE_FAR_TINT_HUES) * smoothstep(least, least + 0.1, saturation);
    if (plant <= 0.0) {
        return albedo;
    }
    float fall = 1.0 - smoothstep(LITE_FAR_TINT_SATURATION_HUES.x, LITE_FAR_TINT_SATURATION_HUES.y, hue);
    ivec2 first = ivec2(floor(at));
    ivec2 foliageRows = ivec2(0, LITE_FAR_SNOW_COLOUR_SIZE);
    vec4 firstFoliage = texelFetch(FarSnowColours, first + foliageRows, 0);
    vec3 target;
    float known;
    if (firstFoliage.a > 254.5 / 255.0) {
        target = lite_far_cell_target(texelFetch(FarSnowColours, first, 0), firstFoliage, saturation, fall, side);
        known = 1.0;
    } else {
        vec2 blend = at - vec2(first);
        target = vec3(0.0);
        float weights = 0.0;
        known = 0.0;
        for (int i = 0; i < 4; i++) {
            ivec2 offset = ivec2(i & 1, i >> 1);
            ivec2 texel = min(first + offset, ivec2(LITE_FAR_SNOW_COLOUR_SIZE - 1));
            vec4 grass = texelFetch(FarSnowColours, texel, 0);
            // Foliage's alpha is 0 on land that is hot all year and where colours aren't known.
            vec4 foliage = texelFetch(FarSnowColours, texel + foliageRows, 0);
            vec2 shares = mix(1.0 - blend, blend, vec2(offset));
            float share = shares.x * shares.y * step(0.5, grass.a) * step(0.5, foliage.a);
            vec3 cell = lite_far_cell_target(grass, foliage, saturation, fall, side);
            float likeness = exp(-pow(lite_far_hue_gap(hue, lite_far_hue(cell)) / 30.0, 2.0));
            float weight = share * (0.05 + likeness);
            target += cell * weight;
            weights += weight;
            known += share;
        }
        if (weights <= 0.0) {
            return albedo;
        }
        target /= weights;
    }
    const vec3 luma = vec3(0.2126, 0.7152, 0.0722);
    vec3 tinted = min(target * dot(albedo, luma) / max(dot(target, luma), 1.0e-3), vec3(1.0));
    return mix(albedo, tinted, plant * known);
}

// What the debug view shows for a pixel: unknown magenta, snow white-blue, clear green, and a
// column that changes orange before its switch and yellow after it; side faces grey.
vec3 lite_far_snow_debug(vec3 color, float state) {
    vec3 tint = state < 0.5 ? vec3(0.5) : state < 1.5 ? vec3(1.0, 0.0, 1.0) : state < 2.5 ? vec3(0.75, 0.9, 1.0)
        : state < 3.5 ? vec3(0.2, 0.8, 0.2) : state < 4.5 ? vec3(1.0, 0.5, 0.0) : vec3(1.0, 0.95, 0.2);
    float shade = dot(color, vec3(0.2126, 0.7152, 0.0722));
    return tint * (0.35 + 0.65 * clamp(shade * 2.0, 0.0, 1.0));
}

// The far pixel's colour as the far mod lit it, with the season's snow put on it or taken off and
// its grass and leaves tinted to the season. footprint is one pixel's size in blocks there
// (lite_far_footprint); water is true where the pass found water. Snow isn't painted on water that
// looks like water: the far mods' coarser detail levels lay their water over flat shore, and that
// shore is still painted.
vec3 lite_far_snow(vec3 color, sampler2D lightmap, vec3 position, vec3 normal, float footprint, bool water) {
    bool debug = FarSnowState.w > 0.5;
    vec3 local = lite_block_local(position);
    ivec2 column = LiteCameraBlock.xz + ivec2(floor(local.xz));
    vec3 light = max(lite_far_vanilla_light(lightmap), vec3(1.0e-3));
    vec3 albedo = color / light;
    // Snow lies on faces that look up, and is painted on top faces (and the far mods' slopes
    // between them); captured snow is taken off steeper ones too, where depth makes a canopy's
    // tops look tilted.
    if (normal.y < LITE_FAR_SNOW_STEEPEST) {
        return debug ? lite_far_snow_debug(color, 0.0) : lite_far_season_tint(albedo, column, true) * light;
    }
    uint h = lite_far_snow_hash(column);
    // Each column's own noise shows only while a column is at least about a pixel wide; further out
    // it would sparkle, and the patches carry on alone.
    float detail = 1.0 - smoothstep(0.7, 2.0, footprint);
    float patches = lite_far_snow_patches(column);
    float noise = mix(patches, 0.6 * patches + 0.4 * lite_far_snow_unit(h), detail);
    // Look the line up a little off the column, so the 16-block texels never show as squares.
    vec2 wobble = mix(vec2(patches, fract(patches * 7.31)), vec2(uvec2(h >> 16, h >> 24) & 15u) / 15.0, detail) - 0.5;
    vec2 codes = lite_far_snow_codes(column + ivec2(floor(wobble * 16.0)));
    float least = min(albedo.r, min(albedo.g, albedo.b));
    float most = max(albedo.r, max(albedo.g, albedo.b));
    bool captured = least > LITE_FAR_SNOW_CAPTURED_LEAST && most - least < LITE_FAR_SNOW_CAPTURED_SPREAD;
    if (codes.x < 0.5 || codes.y < 0.5) {
        // No line here (yet): the snow stays as captured, the plants still follow the season.
        return debug ? lite_far_snow_debug(color, 1.0) : captured ? color : lite_far_season_tint(albedo, column, false) * light;
    }
    float height = float(LiteCameraBlock.y) + local.y - FarSnowState.y + 2.0 + (noise - 0.5) * LITE_FAR_SNOW_LINE_JITTER;
    // A change resumed on joining: the land as captured is what it changes from.
    bool before = FarSnowColourOrigin.z != 0 ? captured : height >= codes.x;
    bool after = height >= codes.y;
    bool snow = after;
    if (before != after) {
        // Higher ground keeps its snow longest and whitens first; within that the noise decides.
        float threshold = clamp(0.8 * noise + 0.2 * clamp((position.y - FarShadowRange.z) / 96.0, 0.0, 1.0) * (after ? -1.0 : 1.0)
            + (after ? 0.2 : 0.0), 0.001, 0.999);
        snow = FarSnowState.x > threshold ? after : before;
    }
    if (debug) {
        bool changing = before != after && FarSnowState.x < 1.0;
        return lite_far_snow_debug(color, changing ? (snow == after ? 5.0 : 4.0) : snow ? 2.0 : 3.0);
    }

    bool watery = water && albedo.b > max(albedo.r, albedo.g) + LITE_FAR_SNOW_WATER_BLUE;
    if (snow && !captured && !watery && normal.y >= LITE_FAR_SNOW_FLATTEST) {
        // Darker ground (forest canopies) under the snow keeps a little of its shading.
        float ground = dot(albedo, vec3(0.2126, 0.7152, 0.0722));
        return LITE_FAR_SNOW_WHITE * light * mix(0.8, 1.0, smoothstep(0.08, 0.4, ground));
    }
    if (!snow && captured) {
        vec4 grass = lite_far_snow_grass(column);
        if (grass.a > 0.5) {
            // As bright, against the snow it replaces, as the grass is against white.
            float shade = dot(albedo, vec3(0.2126, 0.7152, 0.0722)) / dot(LITE_FAR_SNOW_WHITE, vec3(0.2126, 0.7152, 0.0722));
            return grass.rgb * LITE_FAR_SNOW_GRASS_GREY * min(shade, 1.0) * light;
        }
    }
    return captured ? color : lite_far_season_tint(albedo, column, false) * light;
}

#endif
