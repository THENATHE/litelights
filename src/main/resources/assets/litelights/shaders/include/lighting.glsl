#ifndef LITELIGHTS_LIGHTING_GLSL
#define LITELIGHTS_LIGHTING_GLSL

#include <litelights:frame.glsl>

// The LiteLights light model. It always starts from vanilla's lightmap, so whatever the light
// engine produced (ScalableLux, LambDynamicLights' held light, night vision, the Nether's ambient)
// comes through unchanged; shadows only lower the sky-light part of the lightmap coordinate.
//
// lightCoord is vanilla's UV2 attribute as floats (0..240 for each of block and sky light).

vec4 lite_sample_lightmap(sampler2D lightmap, vec2 lightCoord) {
    return texture(lightmap, clamp((lightCoord / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

// How much the surface faces the light that casts shadows, softened so faces almost edge-on to
// the sun don't flicker between lit and unlit.
float lite_light_facing(vec3 normal) {
    return smoothstep(-0.05, 0.3, dot(normal, LiteShadowLightDirection.xyz));
}

// The lightmap colour for a world surface. visibility is the shadow lookup (1 lit, 0 shadowed);
// facing is lite_light_facing (or 1 for thin foliage, which lets light through).
//
// The lightmap colour is split into what block light alone gives and what the sky adds on top.
// Shadows only take away part of the sky's share, so torches, lava and held lights keep their full
// strength inside shadows.
//
// Normal mode only adds shadows: a surface that faces the light loses part of its sky light where a
// caster blocks it, and surfaces turned away keep vanilla's own face shading untouched.
// Enhanced mode relights: surfaces turned away from the light also lose some sky light, lit
// surfaces gain a little and take the sun's colour, unlit ones the cooler sky colour.
vec4 lite_world_light(sampler2D lightmap, vec2 lightCoord, float visibility, float facing) {
    vec4 full = lite_sample_lightmap(lightmap, lightCoord);
    if (lite_feature(LITE_FEATURE_DEBUG_SHADOWS)) {
        // Red: shadow lookup (1 lit), green: light facing, blue: sky light level.
        return vec4(visibility, facing, lightCoord.y / 240.0, 1.0) * 4.0;
    }
    float strength = LiteShadowParams.x * LiteShadowFade.y;
    if (!lite_world_pass() || !lite_feature(LITE_FEATURE_SHADOWS) || strength <= 0.0) {
        return full;
    }
    vec3 blockOnly = lite_sample_lightmap(lightmap, vec2(lightCoord.x, 0.0)).rgb;
    vec3 skyShare = max(full.rgb - blockOnly, vec3(0.0));
    // How much of the sky's light a full shadow takes away, worked out from the extra darkness
    // slider on the CPU (ShadowDepth): 0.46 at 0 %, up to 0.96 (nearly black in full sun) at 100 %.
    float shadowDepth = LiteShadowParams.y * strength;

    if (!lite_feature(LITE_FEATURE_ENHANCED_SHADOWS)) {
        float missing = (1.0 - visibility) * facing;
        return vec4(blockOnly + skyShare * (1.0 - shadowDepth * missing), full.a);
    }

    // Faces turned away from the light are already darkened by vanilla's face shading, so they lose
    // only about half as much as a lit face that a caster blocks.
    float missing = clamp((1.0 - visibility) * facing + (1.0 - facing) * 0.55, 0.0, 1.0);
    float direct = 1.0 - missing;
    // The colour follows the light more strictly than the brightness does: as the sun gets low,
    // faces turned away from it are lit by the sky alone, so they take the sky's cool colour
    // instead of half the sun's orange (orange on a dim face reads as brown). With the sun high
    // this is the same mix as before.
    float lowSun = 1.0 - smoothstep(0.12, 0.45, LiteShadowLightDirection.y);
    float sunColourShare = clamp(1.0 - (1.0 - visibility) * facing - (1.0 - facing) * mix(0.55, 1.0, lowSun), 0.0, 1.0);
    vec3 tint = mix(LiteAmbientColor.rgb, LiteSunColor.rgb * (1.0 + 0.1 * strength), sunColourShare);
    vec3 sky = skyShare * (1.0 - shadowDepth * missing) * mix(vec3(1.0), tint, strength);
    return vec4(blockOnly + sky, full.a);
}

#endif
