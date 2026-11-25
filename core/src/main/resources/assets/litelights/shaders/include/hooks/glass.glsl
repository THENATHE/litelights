#ifndef LITELIGHTS_HOOK_GLASS_GLSL
#define LITELIGHTS_HOOK_GLASS_GLSL

// Owned by the particles-weather stream (weather on glass, include/glass/weather.glsl). Called by
// the terrain fragment shaders (vanilla's and Sodium's) on the block texture's colour, before the
// cut-out test and before lighting, in every pass: the Improved Transparency alpha-only stages must
// see the same alpha as the colour stage, and on clear glass the frost lands on texels the cut-out
// would otherwise throw away. Leaves every other material untouched.
//
// Only the terrain shaders (they name their atlas sampler in LITE_HOST_BLOCK_ATLAS) have the real
// version; it reads the rain tops and the glass windows through the water stream's LiteFluidData, so
// it adds no texture unit (docs/ARCHITECTURE.md, "Texture units"). uv is no longer read (0.1.7 finds
// a window's edges from the world, not the texture's frame); it stays so the hosts' calls stay as they are.
#if defined(LITELIGHTS_SHADOW_GLSL) && defined(LITELIGHTS_MATERIAL_GLSL) && defined(LITE_HOST_BLOCK_ATLAS)
#include <litelights:glass/weather.glsl>

void lite_glass_surface(inout vec4 color, vec3 position, vec3 normal, vec2 uv, vec2 lightCoord, int material) {
    if (!lite_world_pass() || !lite_glass_active() || material != LITE_MATERIAL_GLASS) {
        return;
    }
    ivec3 texelId;
    vec4 weather = lite_glass_weather_layers(position, normal, lightCoord, texelId);
    if (weather.a <= 0.002) {
        return;
    }
#ifdef ALPHA_CUTOUT
    // The cut-out pass doesn't blend, so a texel of weather is either drawn, opaque, or not at all.
    // Fainter weather (the distance fade) takes fewer texels, chosen by a fixed hash so they hold
    // still and go one by one.
    float threshold = 0.12 + 0.46 * lite_glass_hash(texelId);
    if (weather.a < threshold) {
        return;
    }
    // Over the glass's own texels the weather covers a little more than its alpha says, so the
    // glass shows through faintly. The glass counts only as much as it is opaque: a texel the
    // texture's mipmaps or a resource pack leave half clear carries the clear part's colour, never
    // meant to be seen, which would darken the weather along the frame and leave a dark fringe.
    bool glassTexel = color.a >= ALPHA_CUTOUT;
    float cover = glassTexel ? min(1.0, weather.a + 0.2) : 1.0;
    color = vec4(lite_glass_over(vec4(weather.rgb, cover), color).rgb, 1.0);
#else
    // Weather laid over the texel as one layer over another, both straight alpha: where the texture
    // is clear its colour (a grey that is never meant to be seen) must not tint the weather, and
    // where it is stained the glass shows through only as much as the weather leaves uncovered.
    // The alpha is the same as before, so Improved Transparency's alpha-only stages still match.
    color = lite_glass_over(weather, color);
#endif
}

#else

void lite_glass_surface(inout vec4 color, vec3 position, vec3 normal, vec2 uv, vec2 lightCoord, int material) {
}

#endif

#endif
