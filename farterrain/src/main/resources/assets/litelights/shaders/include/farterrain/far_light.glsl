#ifndef LITELIGHTS_FARTERRAIN_FAR_LIGHT_GLSL
#define LITELIGHTS_FARTERRAIN_FAR_LIGHT_GLSL

// Far shadows on LiteLights' own terrain past the shadow map. It works on finished colour, so it
// follows the forward shaders' maths closely enough that the two meet without a seam: the same
// light model (lighting.glsl) and haze, mist and storm fog (skyfog/atmosphere.glsl).
//
// A surface this far out is almost always in the open, so its light is taken as full sky light and
// no block light.

#include <litelights:farterrain/source.glsl>
#include <litelights:skyfog/atmosphere.glsl>
#include <litelights:shadow.glsl>
#include <litelights:lighting.glsl>

const vec2 LITE_FAR_LIGHT_COORD = vec2(0.0, 240.0);

// LiteLights' light on a surface in full sky light, from vanilla's lightmap.
vec3 lite_far_light(sampler2D lightmap, float visibility, float facing) {
    return lite_world_light(lightmap, LITE_FAR_LIGHT_COORD, visibility, facing).rgb;
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

// Far shadow on one of LiteLights' own surfaces past the shadow map. The surface was already lit
// and then fogged, so only its own share of the pixel darkens, by the light model's ratio between
// the light it should have and the light it was given: pixel - (pixel - air) * (1 - ratio).
//
// Over the last tenth of the shadow distance the sun map's shadows thin out (weight 0 to 1 here)
// while far shadows take over. The map's share of a shadow is already in the pixel, so the far
// shadow only adds what is missing: the ratio is taken against the light a surface in the same
// shadow got from the thinned-out map, not against full light. Two half-strength shadows multiplied
// would otherwise come out lighter than one whole shadow, a pale ring through every shadow that
// crosses the band.
vec3 lite_far_shadow_own(vec3 color, sampler2D lightmap, vec3 position, float visibility, float weight, float facing) {
    float given = mix(visibility, 1.0, weight);
    vec3 ratio = lite_far_light(lightmap, visibility, facing) / max(lite_far_light(lightmap, given, facing), vec3(1.0e-4));
    vec3 air = lite_far_air_in_pixel(position);
    return max(color - (color - air) * (1.0 - ratio), vec3(0.0));
}

#endif
