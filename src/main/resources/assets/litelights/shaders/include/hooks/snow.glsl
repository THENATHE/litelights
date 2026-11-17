#ifndef LITELIGHTS_HOOK_SNOW_GLSL
#define LITELIGHTS_HOOK_SNOW_GLSL

// Owned by the snowleaves099 stream (snowy leaves and ground, include/weather/snow_cover.glsl).
// Called by the terrain fragment shaders (vanilla's and Sodium's) on the block texture's colour
// after the leaves under trees and before lighting, outside Improved Transparency's alpha-only
// stages, in world passes only. Costs one uniform test while no snow has gathered.
//
// Only the terrain shaders (they name their atlas sampler in LITE_HOST_BLOCK_ATLAS, and include the
// material texture by now) have the real version; it reads the water stream's LiteFluidData, so it
// adds no texture unit (docs/ARCHITECTURE.md, "Texture units").
#if defined(LITELIGHTS_MATERIAL_GLSL) && defined(LITE_HOST_BLOCK_ATLAS)
#include <litelights:weather/snow_cover.glsl>

void lite_snow_cover(inout vec4 color, vec4 vertexColor, vec3 position, vec3 normal, vec2 lightCoord, int material) {
    if (LiteSnowCover.x <= 0.0) {
        return;
    }
    lite_snow_cover_surface(color, vertexColor, position, normal, lightCoord, material);
}

#else

void lite_snow_cover(inout vec4 color, vec4 vertexColor, vec3 position, vec3 normal, vec2 lightCoord, int material) {
}

#endif

#endif
