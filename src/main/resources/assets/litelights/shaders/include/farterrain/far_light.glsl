#ifndef LITELIGHTS_FARTERRAIN_FAR_LIGHT_GLSL
#define LITELIGHTS_FARTERRAIN_FAR_LIGHT_GLSL

// LiteLights' light, water and air on far terrain that another mod drew (Voxy, Distant Horizons),
// and far shadows on LiteLights' own terrain past the shadow map. Everything works on finished
// colour, so it follows the forward shaders' maths closely enough that the two meet without a
// seam: the same light model (lighting.glsl), night tone (light_post), haze, mist and storm fog
// (skyfog/atmosphere.glsl) and water sky reflection (water/reflection.glsl).
//
// Far terrain has no light levels LiteLights can read. It is far from the camera and almost always
// out in the open, so it is lit as full sky light and no block light, which is also what the
// far mods' own colours were lit with there.

#include <litelights:farterrain/source.glsl>
#include <litelights:skyfog/atmosphere.glsl>
#include <litelights:shadow.glsl>
#include <litelights:lighting.glsl>
#include <litelights:light_post/surface_light.glsl>
#include <litelights:water/surface.glsl>

const vec2 LITE_FAR_LIGHT_COORD = vec2(0.0, 240.0);

// LiteLights' light on a surface in full sky light, from vanilla's lightmap.
vec3 lite_far_light(sampler2D lightmap, float visibility, float facing) {
    return lite_world_light(lightmap, LITE_FAR_LIGHT_COORD, visibility, facing).rgb;
}

// What the far mod lit the surface with: vanilla's lightmap at full sky light.
vec3 lite_far_vanilla_light(sampler2D lightmap) {
    return lite_sample_lightmap(lightmap, LITE_FAR_LIGHT_COORD).rgb;
}

// Shades another mod's far terrain the way the forward shaders shade LiteLights' own: relit by the
// light model (with far shadows), then the night tone.
vec3 lite_far_relight(vec3 color, sampler2D lightmap, float visibility, float facing) {
    vec3 ratio = lite_far_light(lightmap, visibility, facing) / max(lite_far_vanilla_light(lightmap), vec3(1.0e-4));
    color *= ratio;
    if (lite_feature(LITE_FEATURE_NIGHT_TONE)) {
        color = lite_night_tone(color, LITE_FAR_LIGHT_COORD, LiteFeatureValuesC.z);
    }
    return color;
}

// The haze, mist and storm fog in front of a far point, and the End tint, as lite_fog applies them.
// No render-distance fade: while far terrain is drawn, the land doesn't end at the render distance.
vec3 lite_far_fog(vec3 color, vec3 position) {
    if (lite_feature(LITE_FEATURE_END_TINT)) {
        color *= LiteEndTint.rgb;
    }
    if (lite_feature(LITE_FEATURE_LITE_FOG) && lite_feature(LITE_FEATURE_HAZE)) {
        vec4 air = lite_air(position, 1.0);
        color = mix(color, air.rgb, air.a);
    }
    return color;
}

// How much of a fogged pixel's colour is the air in front of it rather than the surface: the
// colour lite_fog mixed in, already weighted (surface * (1 - hidden) + this = the pixel).
vec3 lite_far_air_in_pixel(vec3 position) {
    if (!lite_feature(LITE_FEATURE_LITE_FOG)) {
        return vec3(0.0);
    }
    vec3 air = vec3(0.0);
    if (lite_feature(LITE_FEATURE_HAZE)) {
        vec4 haze = lite_air(position, 1.0);
        air = haze.rgb * haze.a;
    }
    float edge = lite_edge_fog(position);
    return air * (1.0 - edge) + lite_horizon_color(normalize(position)) * edge;
}

// Far shadow on one of LiteLights' own surfaces past the shadow map. The surface was lit as fully
// visible and then fogged, so only its own share of the pixel darkens, by the light model's ratio
// between the shadowed and the unshadowed light: pixel - (pixel - air) * (1 - ratio).
vec3 lite_far_shadow_own(vec3 color, sampler2D lightmap, vec3 position, float visibility, float facing) {
    vec3 ratio = lite_far_light(lightmap, visibility, facing) / max(lite_far_light(lightmap, 1.0, facing), vec3(1.0e-4));
    vec3 air = lite_far_air_in_pixel(position);
    return max(color - (color - air) * (1.0 - ratio), vec3(0.0));
}

// Whether a far point is the sea, and where its surface is. surfaceHeight is the sea surface's
// camera-relative height (sea level minus 1/9 block). Lakes above sea level aren't found this way.
//
// Voxy draws its water without depth, so its depth buffer holds the sea floor: the sea is where
// the view ray crosses the surface on its way down to a point well below it. Distant Horizons
// writes its water's depth, so there the point itself lies on the surface, on a face that looks up.
// Its sea surface reads back within about a third of a block of the true one (its far detail
// levels and depth precision), so a flat shore right at sea level counts as sea too.
bool lite_far_sea_surface(vec3 position, vec3 normal, float surfaceHeight, out vec3 surface) {
    surface = position;
    if (surfaceHeight >= -0.05) {
        return false;
    }
    #ifdef LITE_FAR_SOURCE_DH
    return abs(position.y - surfaceHeight) < 0.35 && normal.y > 0.9;
    #else
    if (position.y > surfaceHeight - 0.3) {
        return false;
    }
    surface = position * (surfaceHeight / position.y);
    return true;
    #endif
}

#endif
