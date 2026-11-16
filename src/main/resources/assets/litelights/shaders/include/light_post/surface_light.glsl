#ifndef LITELIGHTS_LIGHT_POST_SURFACE_LIGHT_GLSL
#define LITELIGHTS_LIGHT_POST_SURFACE_LIGHT_GLSL

#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:surface.glsl>
#include <litelights:light_post/ray_pattern.glsl>
#include <litelights:colour.glsl>

// Surface effects of the light-post stream: enhanced light on glowing blocks, the night tone,
// glint and where light rays land. Everything here works on lit colour, before fog. Fragment
// shaders only: it needs shadow.glsl and pixel_lock.glsl, included before the hooks.

// The face's main axis, for snapping to the face's texel grid. Water surfaces tilt a little where
// they flow, but their texels still follow the block grid.
vec3 lite_lp_axis_normal(vec3 normal) {
    vec3 a = abs(normal);
    if (a.y >= a.x && a.y >= a.z) {
        return vec3(0.0, sign(normal.y), 0.0);
    }
    return a.x >= a.z ? vec3(sign(normal.x), 0.0, 0.0) : vec3(0.0, 0.0, sign(normal.z));
}

// How strongly the sun shines right now: day, sun above the horizon, not too much rain.
float lite_lp_sun_power() {
    float aboveHorizon = smoothstep(0.0, 0.12, LiteSunDirection.w);
    return LiteSunColor.a * aboveHorizon * (1.0 - 0.85 * LiteWeather.x);
}

// How open to the sky a surface is, from its sky light.
float lite_lp_sky_openness(vec2 lightCoord) {
    return smoothstep(0.55, 0.95, lightCoord.y / 240.0);
}

// --- Enhanced light ---------------------------------------------------------------------------

// How much a glowing texel glows: its bright parts fully, its dark parts not at all. Measured on
// the brightest channel, so saturated light (orange lava, red redstone, blue soul fire) counts as
// bright. Bloom's emissive mask uses this.
float lite_glow_amount(vec3 color) {
    return smoothstep(0.4, 0.9, max(color.r, max(color.g, color.b)));
}

// Glowing blocks look hotter (bright parts brighter, their cores a little whiter) and their dark
// parts darker. Keeps each texel's hue, so soul fire stays blue.
vec3 lite_enhanced_glow(vec3 color) {
    float luma = lite_luma(color);
    float hot = smoothstep(0.45, 0.95, luma);
    float dark = 1.0 - smoothstep(0.12, 0.5, luma);
    color *= 1.0 - 0.38 * dark;
    float peak = max(color.r, max(color.g, color.b));
    vec3 heated = color * 1.2 + (vec3(peak) - color) * 0.14;
    return min(mix(color, heated, hot), vec3(1.0));
}

// --- Night tone -------------------------------------------------------------------------------

// At night the share of a surface's light that comes from the sky turns a cool moonlit blue, at
// the same brightness. Light from torches, lava and held lights keeps its warmth, and surfaces
// with no sky light (underground) are left alone.
vec3 lite_night_tone(vec3 color, vec2 lightCoord, float strength) {
    float night = LiteAmbientColor.a;
    if (strength * night <= 0.0 || LiteCameraBlock.w != LITE_DIMENSION_OVERWORLD) {
        return color;
    }
    // Vanilla's brightness curve per light level; moonlight is roughly a third as bright as a
    // torch at the same level.
    float skyLevel = clamp(lightCoord.y / 240.0, 0.0, 1.0);
    float blockLevel = clamp(lightCoord.x / 240.0, 0.0, 1.0);
    float sky = skyLevel / (4.0 - 3.0 * skyLevel) * 0.3;
    float block = blockLevel / (4.0 - 3.0 * blockLevel);
    float skyShare = sky / (sky + block + 0.015);
    float amount = strength * night * skyShare;
    float luma = lite_luma(color);
    // The tint has a luma of 1, so the cool colour is as bright as the original.
    vec3 moonlit = mix(vec3(luma), color, 0.75) * vec3(0.90, 1.0, 1.28);
    return mix(color, moonlit, amount);
}

// --- Glint ------------------------------------------------------------------------------------

// LiteFeatureValuesC.y holds the glint switches (LightPostFrame): which materials glint above
// water, and how the water's sparkles move.
const int LITE_GLINT_WATER = 1;
const int LITE_GLINT_ICE_SNOW = 2;
const int LITE_GLINT_METAL_GLASS = 4;
const int LITE_GLINT_SAND_STONE = 8;
const int LITE_GLINT_MOTION_SHIFT = 4;   // 0 still (viewpoint only), 1 gentle, 2 lively

int lite_lp_glint_switches() {
    return int(LiteFeatureValuesC.y + 0.5);
}

// The switch a material's glint belongs to, or 0 for materials that never glint (plants and
// leaves, glowing blocks). Stone, ores and the other plain blocks go with sand.
int lite_lp_glint_group(int material) {
    if (material == LITE_MATERIAL_WATER) {
        return LITE_GLINT_WATER;
    }
    if (material == LITE_MATERIAL_ICE || material == LITE_MATERIAL_SNOW) {
        return LITE_GLINT_ICE_SNOW;
    }
    if (material == LITE_MATERIAL_METAL || material == LITE_MATERIAL_GLASS) {
        return LITE_GLINT_METAL_GLASS;
    }
    if (material == LITE_MATERIAL_SAND || material == LITE_MATERIAL_DEFAULT) {
        return LITE_GLINT_SAND_STONE;
    }
    return 0;
}

// How bright the glint of each group is, and what share of its cells can catch the sun.
float lite_lp_glint_weight(int group) {
    return group == LITE_GLINT_WATER || group == LITE_GLINT_ICE_SNOW ? 1.0 : group == LITE_GLINT_METAL_GLASS ? 0.75 : 0.5;
}

float lite_lp_glint_share(int group) {
    return group == LITE_GLINT_WATER ? 0.4 : group == LITE_GLINT_SAND_STONE ? 0.035 : 0.6;
}

// Screen pixels per block on a surface at this camera-relative position, from the level's
// projection: the projection's vertical scale is the length of the y row of the view-projection
// matrix (the view part only rotates).
float lite_lp_pixels_per_block(vec3 position) {
    vec4 clip = LiteViewProjection * vec4(position, 1.0);
    float scale = length(vec3(LiteViewProjection[0][1], LiteViewProjection[1][1], LiteViewProjection[2][1]));
    return LiteScreen.y * 0.5 * scale / max(clip.w, 0.05);
}

// Sun sparkle on blocks and on the water surface. Each glint cell (a texel when pixel lock is on)
// has its own fixed tilt: at low softness the tilts are large and the highlight sharp, so a few
// cells flash; at high softness the highlight spreads into a sheen. Which cells catch the sun
// depends on where they are seen from, so the sparkles shift as the viewer moves. On water a slow
// wave drift turns the tilts over a few seconds and each sparkle fades in and out over about a
// third of a second (Water sparkle motion: Still keeps only the viewpoint). Far water, where a cell
// is smaller than a pixel, gets a soft sheen instead of dots. Faces turned away from the sun never
// glint; sand and stone only glimmer, rarely, with the sun low.
vec3 lite_glint(LiteSurface surface) {
    bool underwater = lite_feature(LITE_FEATURE_CAMERA_UNDERWATER);
    if (underwater ? !lite_feature(LITE_FEATURE_UNDERWATER_GLINT) : !lite_feature(LITE_FEATURE_TERRAIN_GLINT)) {
        return vec3(0.0);
    }
    int switches = lite_lp_glint_switches();
    int group = lite_lp_glint_group(surface.material);
    bool water = group == LITE_GLINT_WATER;
    // Under water every lit surface may sparkle (Glint under water); above it, each group has its switch.
    if (group == 0 || (!underwater && (switches & group) == 0)) {
        return vec3(0.0);
    }

    vec3 axis = lite_lp_axis_normal(surface.normal);
    // Seen from below, the water surface's normal faces down, but the sun lights it from above.
    vec3 litSide = underwater && water ? vec3(0.0, 1.0, 0.0) : axis;
    float towardSun = dot(litSide, LiteSunDirection.xyz);
    float facesSun = water ? smoothstep(0.0, 0.06, towardSun) : smoothstep(0.03, 0.2, towardSun);
    float strength = lite_lp_glint_weight(group) * (underwater ? 0.6 : 1.0) * lite_lp_sky_openness(surface.lightCoord)
        * lite_lp_sun_power() * surface.shadow * facesSun;
    if (group == LITE_GLINT_SAND_STONE && !underwater) {
        // Sand and stone: only a glimmer while the sun is low.
        strength *= 1.0 - smoothstep(0.2, 0.42, LiteSunDirection.w);
    }
    if (strength <= 0.001) {
        return vec3(0.0);
    }

    bool locked = lite_locked(LITE_LOCK_GLINT);
    vec3 position = locked ? lite_pixel_lock_face(surface.position, axis) : surface.position;
    float cellsPerBlock = locked ? LITE_TEXELS_PER_BLOCK : 48.0;
    vec3 inside = lite_lp_pattern_position(position) - axis * (0.25 / cellsPerBlock);
    vec3 cell = floor(inside * cellsPerBlock);
    vec3 random = lite_hash33(cell) - 0.5;
    vec3 chance = lite_hash33(cell + vec3(71.0, 13.0, 37.0));
    float time = LiteTime.y;
    int motion = water ? (switches >> LITE_GLINT_MOTION_SHIFT) & 3 : 0;

    float smoothness = LiteFeatureValuesC.x;
    // Water's tilts stay within what its small waves give, so its sparkles gather around the sun's
    // reflection instead of dotting the whole lake.
    vec3 tilt = random * mix(water ? 0.5 : 1.1, 0.04, smoothness);
    float sparkle = step(chance.x, lite_lp_glint_share(group));
    if (motion > 0) {
        // The slow wave drift: every cell's tilt turns a little over a few seconds (periods that
        // divide the 3600 s the clock wraps at, so nothing jumps).
        float drift = motion == 1 ? 0.10 : 0.22;
        float turn = 6.28318530718 * (time / (motion == 1 ? 4.0 : 2.0) + chance.y);
        tilt += vec3(cos(turn), 0.0, sin(turn)) * drift * (0.5 + chance.z);
        // And each sparkle comes and goes, fading over about a third of a second.
        float period = motion == 1 ? 3.0 : 1.5;
        float pulse = sin(6.28318530718 * (time / period + chance.z));
        sparkle *= smoothstep(0.0, 1.0, pulse * max(period * 0.48, 1.2));
    }
    vec3 microNormal = normalize(surface.normal + tilt);
    float shininess = mix(700.0, 28.0, smoothness);
    float energy = mix(3.2, 0.35, smoothness);
    vec3 viewDirection = normalize(position - lite_view_origin());

    float highlight;
    if (underwater && water) {
        // Seen from below, the sun shows through the surface where the refracted sunlight comes
        // straight toward the eye.
        vec3 up = -microNormal;
        vec3 sunlight = refract(-LiteSunDirection.xyz, up, 1.0 / 1.33);
        highlight = pow(max(dot(-viewDirection, -sunlight), 0.0), shininess * 0.5);
    } else {
        vec3 halfway = normalize(LiteSunDirection.xyz - viewDirection);
        highlight = pow(max(dot(microNormal, halfway), 0.0), shininess);
        if (water) {
            // Far water: once a cell is smaller than a pixel the dots would only shimmer, so they
            // give way to a soft sheen around the sun's reflection.
            float facing = max(abs(dot(viewDirection, axis)), 0.08);
            float cellsPerPixel = cellsPerBlock / (lite_lp_pixels_per_block(surface.position) * facing);
            float far = smoothstep(0.35, 1.2, cellsPerPixel);
            float sheen = pow(max(dot(axis, halfway), 0.0), 90.0) * 0.12;
            return LiteSunColor.rgb * (mix(highlight * sparkle * energy, sheen, far) * strength);
        }
    }
    if (group == LITE_GLINT_SAND_STONE) {
        energy *= 0.5;
    }
    return LiteSunColor.rgb * (highlight * sparkle * energy * strength);
}

// --- Where light rays land --------------------------------------------------------------------

// Extra light, as a multiple of the surface colour, where light rays land. Under water the ray
// pattern lands as moving shafts of light on the floor. In air, a sunlit spot whose surroundings
// are shaded (a sun patch on a forest floor, light through a window) is where visible beams end,
// so it glows a little warmer; open ground gets nothing.
vec3 lite_ray_landing(LiteSurface surface, float strength) {
    // Beams land on the ground and on walls, not on the leaves they come through.
    if (lite_material_is_foliage(surface.material)) {
        return vec3(0.0);
    }
    float light = strength * lite_lp_sky_openness(surface.lightCoord) * lite_lp_sun_power() * surface.shadow * surface.facing;
    if (light <= 0.01 || LiteShadowLightDirection.w > 0.5) {
        return vec3(0.0);
    }
    vec3 axis = lite_lp_axis_normal(surface.normal);
    vec3 position = lite_locked(LITE_LOCK_LIGHT_RAYS) ? lite_pixel_lock_face(surface.position, axis) : surface.position;

    if (lite_feature(LITE_FEATURE_CAMERA_UNDERWATER)) {
        float shafts = lite_lp_ray_pattern(position, true);
        return vec3(0.75, 0.95, 1.0) * (0.12 * light * shafts);
    }
    if (!lite_feature(LITE_FEATURE_SUN_MAP)) {
        return vec3(0.0);
    }
    vec3 along = LiteShadowLightDirection.xyz;
    vec3 side = normalize(cross(along, abs(along.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 across = cross(along, side);
    vec3 base = lite_shadow_offset(position, axis, LiteShadowMapInfo.y);
    const float reach = 1.75;
    float litAround = lite_shadow_lookup(LiteSunShadow, LiteSunShadowMatrix, base + side * reach, LiteShadowMapInfo.x, 0.02)
        + lite_shadow_lookup(LiteSunShadow, LiteSunShadowMatrix, base - side * reach, LiteShadowMapInfo.x, 0.02)
        + lite_shadow_lookup(LiteSunShadow, LiteSunShadowMatrix, base + across * reach, LiteShadowMapInfo.x, 0.02)
        + lite_shadow_lookup(LiteSunShadow, LiteSunShadowMatrix, base - across * reach, LiteShadowMapInfo.x, 0.02);
    float shadedAround = 1.0 - litAround * 0.25;
    return LiteSunColor.rgb * (0.07 * light * shadedAround * lite_lp_ray_pattern(position, false));
}

#endif
