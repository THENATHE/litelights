#ifndef LITELIGHTS_HOOK_LIGHT_POST_GLSL
#define LITELIGHTS_HOOK_LIGHT_POST_GLSL

// Owned by the light-post stream. Called for every shaded fragment (terrain, block models,
// entities, items) after lighting and the water hook, before fog, in world passes only.
//
// LiteFeatureValuesC (see LightPostFrame): x the water's glint tuning, y the glint switches and the
// ground's glint tuning, z night tone strength, w light-ray landing strength.
//
// The vertex shaders include the hooks too (for the vegetation hook), and they can't take screen
// derivatives, which pixel_lock.glsl uses. Every LiteLights fragment shader includes shadow.glsl
// before the hooks, so its include guard tells the two stages apart.
#ifdef LITELIGHTS_SHADOW_GLSL

#include <litelights:light_post/surface_light.glsl>
#include <litelights:emissive.glsl>
#ifdef LITE_HOST_BLOCK_ATLAS
#include <litelights:light_post/ore_glint.glsl>
#endif

void lite_light_surface(inout vec4 color, LiteSurface surface) {
    bool emissive = lite_material_is_emissive(surface.material);
    bool onBlock = surface.kind == LITE_SURFACE_TERRAIN || surface.kind == LITE_SURFACE_BLOCK_MODEL;

    if (emissive && lite_feature(LITE_FEATURE_ENHANCED_LIGHT)) {
        color.rgb = lite_enhanced_glow(color.rgb);
    }
    if (!emissive && lite_feature(LITE_FEATURE_NIGHT_TONE)) {
        color.rgb = lite_night_tone(color.rgb, surface.lightCoord, LiteFeatureValuesC.z);
    }
    if (onBlock && !emissive) {
        if (lite_feature(LITE_FEATURE_TERRAIN_GLINT) || lite_feature(LITE_FEATURE_UNDERWATER_GLINT)) {
            color.rgb += lite_glint(surface);
        }
#ifdef LITE_HOST_BLOCK_ATLAS
        if (lite_feature(LITE_FEATURE_TERRAIN_GLINT)) {
            color.rgb += lite_ore_glint(surface);
        }
#endif
        if (lite_feature(LITE_FEATURE_RAY_LANDING)) {
            color.rgb += color.rgb * lite_ray_landing(surface, LiteFeatureValuesC.w);
        }
    }

#ifndef OIT_ACCUMULATE
    // Bloom's emissive mask rides in the main target's alpha (emissive.glsl).
    if (emissive && lite_feature(LITE_FEATURE_BLOOM_MASK) && color.a >= LITE_EMISSIVE_OPAQUE) {
        float glow = lite_glow_amount(color.rgb);
        if (glow > 0.0) {
            color.a = lite_emissive_to_alpha(glow);
        }
    }
#endif
}

#else

void lite_light_surface(inout vec4 color, LiteSurface surface) {
}

#endif

#endif
