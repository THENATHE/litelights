#ifndef LITELIGHTS_HOOK_PUDDLES_GLSL
#define LITELIGHTS_HOOK_PUDDLES_GLSL

// Owned by the rain099 stream (puddles, include/weather/puddles.glsl). Called by the terrain
// fragment shaders (vanilla's and Sodium's) after lighting and the water hook, before the
// light-post hook and fog, in world passes only. Leaves every surface that can't hold a puddle
// untouched, and costs one uniform test while there are none.
//
// Only the terrain shaders (they name their atlas sampler in LITE_HOST_BLOCK_ATLAS, and include
// pixel lock and the material texture by now) have the real version; it reads the rain tops through
// the water stream's LiteFluidData, so it adds no texture unit (docs/ARCHITECTURE.md, "Texture units").
#if defined(LITELIGHTS_PIXEL_LOCK_GLSL) && defined(LITELIGHTS_MATERIAL_GLSL) && defined(LITE_HOST_BLOCK_ATLAS)
#include <litelights:weather/puddles.glsl>

void lite_puddles(inout vec4 color, LiteSurface surface) {
    if (LitePuddles.x <= 0.0) {
        return;
    }
    // Derivatives first, while every invocation is still running the same code.
    vec3 dpdx = dFdx(surface.position);
    vec3 dpdy = dFdy(surface.position);
    lite_puddle_surface(color, surface, dpdx, dpdy);
}

#else

void lite_puddles(inout vec4 color, LiteSurface surface) {
}

#endif

#endif
