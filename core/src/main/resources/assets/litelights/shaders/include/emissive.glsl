#ifndef LITELIGHTS_EMISSIVE_GLSL
#define LITELIGHTS_EMISSIVE_GLSL

// The emissive mask the light-post surface hook leaves in the main target's alpha during the
// level, for bloom; the tonemap writes alpha back to 1. Alpha below LITE_EMISSIVE_ALPHA_LIMIT marks a
// glowing texel, 0 the brightest. Nothing else leaves alpha that low on a drawn pixel: opaque
// surfaces write about 1, cut-out ones are discarded below 0.1, and blending over an opaque pixel
// keeps 1. The cleared sky can be 0, so the decoder also wants to know whether the pixel has depth.
// Marks never use alpha 0 itself: other mods clear alpha to 0 over pixels LiteLights drew (Distant
// Horizons' fade pass does, behind its clouds), and that must not read as the brightest glow.
const float LITE_EMISSIVE_ALPHA_LIMIT = 0.09;
// Marks span 0 to this, a little under the limit so 8-bit rounding never crosses it.
const float LITE_EMISSIVE_ALPHA_RANGE = 0.08;
// Only texels at least this opaque are marked, so blended surfaces keep their real alpha.
const float LITE_EMISSIVE_OPAQUE = 0.99;
// The lowest alpha a mark uses, and the alpha below which a pixel is someone else's cleared 0.
const float LITE_EMISSIVE_ALPHA_FLOOR = 1.0 / 255.0;
const float LITE_EMISSIVE_ALPHA_CLEARED = 0.5 / 255.0;

// The alpha that marks a texel glowing this much (0..1).
float lite_emissive_to_alpha(float glow) {
    return LITE_EMISSIVE_ALPHA_FLOOR + (LITE_EMISSIVE_ALPHA_RANGE - LITE_EMISSIVE_ALPHA_FLOOR) * (1.0 - glow);
}

float lite_emissive_from_alpha(float alpha, bool hasDepth) {
    if (!hasDepth || alpha >= LITE_EMISSIVE_ALPHA_LIMIT || alpha < LITE_EMISSIVE_ALPHA_CLEARED) {
        return 0.0;
    }
    return clamp(1.0 - (alpha - LITE_EMISSIVE_ALPHA_FLOOR) / (LITE_EMISSIVE_ALPHA_RANGE - LITE_EMISSIVE_ALPHA_FLOOR), 0.0, 1.0);
}

#endif
