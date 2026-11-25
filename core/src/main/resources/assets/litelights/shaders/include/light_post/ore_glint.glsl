#ifndef LITELIGHTS_LIGHT_POST_ORE_GLINT_GLSL
#define LITELIGHTS_LIGHT_POST_ORE_GLINT_GLSL

#include <litelights:light_post/surface_light.glsl>

// Glint on ores and crystals (shimmer099). The ore in an ore block twinkles in its own colour, and
// amethyst, diamond and emerald blocks sparkle all over. Unlike the ground glint it needs no sun:
// each sparkle catches a light from the eye's side, so it shows in a pitch-dark cave and from most
// angles, and flares now and then on its own. The sun adds its own glint on faces it lights. Only
// on the faces drawn, so nothing shows through walls; it fades out between 24 and 48 blocks.
//
// Ore sparkle motion (oreglint) says what the eye is. Gentle: each sparkle cell has an eye of its
// own that wanders slowly about the face's normal on the clock, so the sparkles come and go by
// themselves and stay as they are while the view moves. Still: it is the real eye, as in 0.9.9,
// with its angle from the normal scaled down by the pace, so the sparkles swap as slowly as they
// twinkle.
//
// Which texels are ore is worked out per texel from the texture itself: the rock the ore texture is
// drawn on comes with the material (LITE_MATERIAL_ORE_*), and LiteOreRocks says what that rock looks
// like in the textures in use (render.material.OreRocks). A texel outside the rock's colours is ore.
//
// Needs the block atlas (LITE_HOST_BLOCK_ATLAS): only the terrain and block-model shaders have it.

const float LITE_ORE_FADE_START = 24.0;
const float LITE_ORE_FADE_END = 48.0;
// How far outside its rock's colours a texel must lie to count as ore, softly: the rock's own
// texels lie at 0 or below, the ore's flecks well past the end.
const float LITE_ORE_EDGE_START = 0.03;
const float LITE_ORE_EDGE_END = 0.10;
// The share of ore texels that can sparkle, and of a crystal's texels (all of a crystal is crystal,
// so far fewer of them, or the whole block would glitter).
const float LITE_ORE_SHARE = 0.7;
const float LITE_CRYSTAL_SHARE = 0.035;
// How bright a sparkle gets at its peak, for ores and for crystals, and the faint light every ore
// texel gives off on its own, so a vein shows in a dark cave even between twinkles.
const float LITE_ORE_SPARKLE = 2.5;
const float LITE_CRYSTAL_SPARKLE = 1.2;
const float LITE_ORE_GLOW = 0.12;
// How fast the sparkles twinkle and flare, against 0.9.9's speed. At a pace of 1 a cell flares
// every 2 to 5 seconds and Gentle's eye wanders over 2 and 3 seconds; every period is that over
// the pace. 60 x the pace must be a whole number, so the periods divide the hour the clock wraps
// at and nothing jumps.
const float LITE_ORE_PACE = 0.1;
// How far Gentle's eye wanders from the normal, across the face (0.6 is about 31 degrees each
// way): the cells' tilts reach about 38 degrees, so most of them catch it now and then.
const float LITE_ORE_WANDER = 0.6;
const int LITE_GLINT_ORES_STILL = 128;   // Ore sparkle motion Still (LightPostFrame.glintSwitches)

// How far a texel's colour lies outside its rock's colours: 0 or less inside them.
float lite_ore_outside(vec3 texel, int rock) {
    vec4 tint = LiteOreRocks[rock * 2];
    vec2 lumaRange = LiteOreRocks[rock * 2 + 1].xy;
    float luma = dot(texel, vec3(0.2126, 0.7152, 0.0722));
    float lumaOutside = max(lumaRange.x - luma, luma - lumaRange.y);
    float colourOutside = length(texel - vec3(luma) - tint.rgb) - tint.a;
    return max(lumaOutside, colourOutside);
}

// The eye a sparkle cell catches its light from. Gentle: it wanders over two axes of the face, on
// periods of 3 and 2 seconds over the pace and a phase of the cell's own. Still: the real eye
// turned toward the normal, its angle times the pace.
vec3 lite_ore_eye(vec3 normal, vec3 axis, vec3 toEye, vec2 phase, bool still) {
    if (still) {
        float facing = clamp(dot(normal, toEye), -1.0, 1.0);
        vec3 across = toEye - normal * facing;
        float acrossLength = length(across);
        if (acrossLength < 0.0001) {
            return normal;
        }
        float angle = acos(facing) * LITE_ORE_PACE;
        return normal * cos(angle) + across * (sin(angle) / acrossLength);
    }
    vec2 turn = 6.28318530718 * (LiteTime.y * LITE_ORE_PACE / vec2(3.0, 2.0) + phase);
    return normalize(normal + LITE_ORE_WANDER * (axis.yzx * sin(turn.x) + axis.zxy * sin(turn.y)));
}

vec3 lite_ore_glint(LiteSurface surface) {
    bool crystal = surface.material == LITE_MATERIAL_CRYSTAL;
    if (!crystal && !lite_material_is_ore(surface.material)) {
        return vec3(0.0);
    }
    int switches = lite_lp_glint_switches();
    if ((switches & LITE_GLINT_ORES) == 0) {
        return vec3(0.0);
    }
    // The ground glint's sliders: intensity at its default (half) gives the full sparkle here.
    vec2 tuning = lite_lp_glint_tuning(switches >> 8);
    float strength = 2.0 * tuning.y * (1.0 - smoothstep(LITE_ORE_FADE_START, LITE_ORE_FADE_END, length(surface.position)));
    if (strength <= 0.001) {
        return vec3(0.0);
    }

    // The texture's own texel at full detail, before tint and light: is it ore, and what colour.
    vec3 texel = texelFetch(LITE_HOST_BLOCK_ATLAS, ivec2(surface.uv * surface.textureTexels), 0).rgb;
    float ore = crystal ? 1.0
        : smoothstep(LITE_ORE_EDGE_START, LITE_ORE_EDGE_END, lite_ore_outside(texel, surface.material - LITE_MATERIAL_ORE_STONE));
    if (ore <= 0.0) {
        return vec3(0.0);
    }
    // The ore's colour: the texel's hue at full brightness, a little toward white. Dark, dull ore
    // (coal) gives a faint grey-white.
    float peak = max(texel.r, max(texel.g, texel.b));
    vec3 colour = mix(texel / max(peak, 0.04), vec3(1.0), 0.3) * mix(0.35, 1.0, peak) * (ore * strength);
    vec3 glow = crystal ? vec3(0.0) : colour * LITE_ORE_GLOW;

    // One sparkle cell per texel of the block grid, fixed in the world like the ground glint's.
    vec3 axis = lite_lp_axis_normal(surface.normal);
    vec3 inside = lite_lp_pattern_position(lite_pixel_lock_face(surface.position, axis)) - axis * (0.25 / LITE_TEXELS_PER_BLOCK);
    vec3 cell = floor(inside * LITE_TEXELS_PER_BLOCK);
    vec3 random = lite_hash33(cell) - 0.5;
    vec3 chance = lite_hash33(cell + vec3(71.0, 13.0, 37.0));
    if (chance.x > (crystal ? LITE_CRYSTAL_SHARE : LITE_ORE_SHARE)) {
        return glow;
    }

    // Wide tilts and a broad highlight: from almost any angle a few cells face the eye.
    float softness = clamp(tuning.x, 0.0, 1.0);
    float crisp = max(-tuning.x, 0.0);
    float shininess = mix(60.0, 18.0, softness) * (1.0 + 2.0 * crisp);
    vec3 microNormal = normalize(surface.normal + random * 1.6);
    vec3 toEye = normalize(lite_view_origin() - surface.position);
    vec3 eye = lite_ore_eye(surface.normal, axis, toEye, fract(chance.zy * vec2(4.0, 8.0)),
        (switches & LITE_GLINT_ORES_STILL) != 0);
    float caught = pow(max(dot(microNormal, eye), 0.0), shininess);
    // Each cell also flares now and then by itself, on the shared clock (still while paused), every
    // 2 to 5 seconds over the pace.
    float period = (2.0 + floor(chance.z * 4.0)) / LITE_ORE_PACE;
    float pulse = 0.5 + 0.5 * sin(6.28318530718 * (LiteTime.y / period + chance.y));
    float flare = pow(pulse, 16.0) * 0.6;
    // Its own faint light in the dark; brighter where torches or lava light the face.
    float blockLight = clamp(surface.lightCoord.x / 240.0, 0.0, 1.0);
    float own = (caught + flare) * mix(0.6, 1.0, blockLight);
    vec3 halfway = normalize(LiteSunDirection.xyz + eye);
    float sun = pow(max(dot(microNormal, halfway), 0.0), shininess) * 1.5
        * lite_lp_sun_power() * surface.shadow * lite_lp_sky_openness(surface.lightCoord);
    return glow + colour * ((own + sun) * (crystal ? LITE_CRYSTAL_SPARKLE : LITE_ORE_SPARKLE));
}

#endif
