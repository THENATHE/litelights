#ifndef LITELIGHTS_HOOK_WATER_GLSL
#define LITELIGHTS_HOOK_WATER_GLSL

// Owned by the water stream. Called for every shaded terrain and block-model fragment after
// lighting and before fog. Changes color for water, ice and lava surfaces, and adds caustics on
// surfaces under water. Leaves other materials untouched.
//
// Every shader includes the hook files, vertex shaders too (for the vegetation hook), but only the
// terrain and block-model fragment shaders call this one. Those are the shaders that have both the
// material texture and pixel lock included by now; pixel lock uses screen derivatives, which a
// vertex shader can't compile, so its include guard doubles as the "fragment shader" test.
//
// lite_water_oit_alpha is the Improved Transparency alpha-only stages' part: those stages skip
// lighting and lite_water_surface, but must see the alpha the colour stage gives a water top face.
#if defined(LITELIGHTS_PIXEL_LOCK_GLSL) && defined(LITELIGHTS_MATERIAL_GLSL)
#include <litelights:water/surface.glsl>
#else
void lite_water_surface(inout vec4 color, LiteSurface surface) {
}

void lite_water_oit_alpha(inout vec4 color, LiteSurface surface) {
}
#endif

#endif
