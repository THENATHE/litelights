#ifndef LITELIGHTS_HOOK_SKY_FOG_GLSL
#define LITELIGHTS_HOOK_SKY_FOG_GLSL

// Owned by the sky-fog stream. Return true after writing the fogged colour to color to replace
// vanilla's fog for this fragment; return false to keep vanilla's fog.
//
// Atmospheric fog is three media seen along the view ray, each with its own colour: the height
// haze, ground mist and storm fog. Their optical depths add up; the colour of what they hide the
// surface behind is the depth-weighted mix of their colours. On top, the render-distance edge
// fades land into the horizon colour the sky pass draws, so terrain meets the sky without a seam.
// In water, lava, powder snow and under blindness the Java side leaves LITE_FEATURE_LITE_FOG off
// and vanilla's fog applies.

#include <litelights:skyfog/atmosphere.glsl>

// True when LiteLights' fog stands in for vanilla's on this fragment (lite_fog will return true).
bool lite_fog_replaces_vanilla() {
    return lite_world_pass() && lite_feature(LITE_FEATURE_LITE_FOG);
}

// What the land fades into at the edge of the render distance, seen in a direction: the sky's
// horizon colour, or without a sky the frame's clear colour (vanilla's fog colour), which is what
// lies behind the land there. premultiply is the fragment's alpha in the OIT accumulate stage, else 1.
vec3 lite_fog_edge_color(vec3 direction, vec4 vanillaFogColor, float premultiply) {
    return lite_feature(LITE_FEATURE_SKYLESS) ? vanillaFogColor.rgb : lite_horizon_color(direction) * premultiply;
}

// The colour a newly loaded chunk fades in from: the same edge colour, so a chunk appears out of
// the air around it rather than out of vanilla's fog colour. color is the fragment after lite_fog;
// in the OIT accumulate stage it is premultiplied, and so is the result.
vec3 lite_fade_in_color(vec4 color, LiteSurface surface, vec4 vanillaFogColor) {
    #ifdef OIT_ACCUMULATE
    float premultiply = color.a;
    #else
    float premultiply = 1.0;
    #endif
    return lite_fog_edge_color(normalize(surface.position), vanillaFogColor, premultiply);
}

bool lite_fog(inout vec4 color, LiteSurface surface, vec4 vanillaFogColor) {
    if (lite_feature(LITE_FEATURE_END_TINT)) {
        color.rgb *= LiteEndTint.rgb;
    }
    if (!lite_feature(LITE_FEATURE_LITE_FOG)) {
        return false;
    }

    #ifdef OIT_ACCUMULATE
    // The accumulate stage works in premultiplied colour, so the fog colour is premultiplied too.
    float premultiply = color.a;
    #else
    float premultiply = 1.0;
    #endif

    vec3 position = surface.position;
    float distance = max(length(position), 1.0e-3);
    vec3 direction = position / distance;

    if (lite_feature(LITE_FEATURE_HAZE)) {
        vec4 air = lite_air(position, clamp(surface.lightCoord.y / 240.0, 0.0, 1.0));
        color.rgb = mix(color.rgb, air.rgb * premultiply, air.a);
    }

    // Without a sky, what lies past the render distance is the frame's clear colour, which is
    // vanilla's fog colour, so the edge fades to that.
    vec3 edge = lite_fog_edge_color(direction, vanillaFogColor, premultiply);
    color.rgb = mix(color.rgb, edge, lite_edge_fog(position));
    return true;
}

#endif
