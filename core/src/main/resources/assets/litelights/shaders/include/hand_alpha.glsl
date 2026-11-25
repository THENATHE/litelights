#ifndef LITELIGHTS_HAND_ALPHA_GLSL
#define LITELIGHTS_HAND_ALPHA_GLSL

#include <litelights:frame.glsl>

// The hand is drawn over the finished level, whose alpha still holds bloom's emissive marks
// (emissive.glsl), and its opaque draws write full alpha so a held item never carries a mark from
// what lies behind it (or a low texture alpha of its own) into the bloom. Opaque draws are those of
// pipelines that don't blend, which are built with LITELIGHTS_OPAQUE_OUTPUT
// (mixin.render.RenderPipelineBuilderMixin); nothing reads their alpha but bloom.
void lite_hand_alpha(inout vec4 color) {
    #ifdef LITELIGHTS_OPAQUE_OUTPUT
    if (LiteFlags.z == LITE_PASS_HAND) {
        color.a = 1.0;
    }
    #endif
}

#endif
