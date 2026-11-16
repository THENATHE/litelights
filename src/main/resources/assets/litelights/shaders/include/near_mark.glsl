#ifndef LITELIGHTS_NEAR_MARK_GLSL
#define LITELIGHTS_NEAR_MARK_GLSL

#include <litelights:frame.glsl>
#include <litelights:emissive.glsl>

// When another mod draws far terrain into the main target in the same frame (Voxy), LiteLights'
// own opaque surfaces write this alpha instead of 1, so the far-terrain pass can tell its pixels
// (already shaded and fogged) from the far mod's (alpha 1). It sits far above the emissive band
// (emissive.glsl), so bloom reads it as an ordinary surface, and the tonemap writes alpha back to 1.
const float LITE_NEAR_ALPHA = 254.0 / 255.0;

// Pipelines that don't blend (solid and cut-out terrain, opaque entities, items and block models)
// are built with LITELIGHTS_OPAQUE_OUTPUT. Nothing reads their alpha but the marks, so every
// fragment above the emissive band is marked: mipmapped and filtered cut-out texels (distant
// leaves and grass) and sprite edges come out a little under 1, and unmarked they were taken for
// the far mod's terrain and hazed a second time. A blended surface keeps its real alpha and is
// only marked where it is fully opaque.
//
// The hand is drawn over the finished level, whose alpha still holds bloom's emissive marks, and its
// opaque draws write full alpha so a held item never carries a mark from what lies behind it (or a
// low texture alpha of its own) into the bloom.
void lite_mark_near(inout vec4 color) {
    #ifdef LITELIGHTS_OPAQUE_OUTPUT
    if (LiteFlags.z == LITE_PASS_HAND) {
        color.a = 1.0;
        return;
    }
    #endif
    if (!lite_feature(LITE_FEATURE_MARK_NEAR) || !lite_world_pass()) {
        return;
    }
    #ifdef LITELIGHTS_OPAQUE_OUTPUT
    bool mark = color.a >= LITE_EMISSIVE_ALPHA_LIMIT;
    #else
    bool mark = color.a >= 0.999;
    #endif
    if (mark) {
        color.a = LITE_NEAR_ALPHA;
    }
}

#endif
