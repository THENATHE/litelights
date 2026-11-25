#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:loop_caps.glsl>

// Spreads the outlined pixels of the level, each by its own amount: every pixel adds a Gaussian
// with its own sigma, so a distant item gets a thin glow and a near one a wide glow in the same
// frame. Across (LITE_OUTLINE_ACROSS) reads outline_level_prepare's (coverage, sigma) and writes the
// spread coverage with the sigma it carries (spread, spread x sigma), so the second pass, down,
// spreads each row by its own sigma into the glow strength. With one sigma everywhere this is the
// ordinary separable Gaussian blur.

uniform sampler2D InputSampler;

layout(push_constant) uniform Spread {
    vec4 Step;    // xy one mask pixel along the spread, in texture units; z pixels reached each way
};

// The safety cap (loop_caps.glsl): EnchantmentOutline reaches at most 64 pixels each way (MAX_TAPS).
const int LITE_OUTLINE_MAX_REACH = 96;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// A Gaussian's weight at this many pixels out. It is divided by its sum over whole pixels, which
// is about sigma x sqrt(2 pi), and at least the centre's weight of 1 for the thinnest ones.
float weight(float offset, float sigma) {
    return exp(-offset * offset / (2.0 * sigma * sigma)) / max(1.0, 2.5066283 * sigma);
}

void main() {
    int reach = lite_loop_count(Step.z, LITE_OUTLINE_MAX_REACH);
    float spread = 0.0;
    float spreadSigma = 0.0;
    for (int i = -reach; i <= reach; i++) {
        vec2 here = texture(InputSampler, texCoord + Step.xy * float(i)).rg;
        if (here.r <= 0.0) {
            continue;
        }
        #ifdef LITE_OUTLINE_ACROSS
        float sigma = here.g;
        #else
        float sigma = here.g / here.r;
        #endif
        float amount = here.r * weight(float(i), sigma);
        spread += amount;
        spreadSigma += amount * sigma;
    }
    #ifdef LITE_OUTLINE_ACROSS
    fragColor = vec4(spread, spreadSigma, 0.0, 1.0);
    #else
    fragColor = vec4(spread, spread, spread, 1.0);
    #endif
}
