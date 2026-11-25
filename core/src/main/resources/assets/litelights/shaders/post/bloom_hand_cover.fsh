#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:post_common.glsl>

// Bloom read the level before the hand was drawn, so the lava or fire behind a held item is still in
// the prefiltered quarter-resolution level, and its glow would spread around and over the item. This
// pass runs once the hand is drawn: each output texel takes out the share of its 4x4 screen pixels the
// hand now covers, and puts in the hand's own glow instead (only pixels the hand marks as glowing,
// which a held item doesn't unless it really glows). The prefilter's sum can't be split per pixel, so
// the level's part is scaled by the share left uncovered.

uniform sampler2D PrefilterSampler;   // bloom_prefilter's output, a quarter of the screen per axis
uniform sampler2D SceneSampler;       // the main colour with the hand drawn
uniform sampler2D HandDepthSampler;   // the hand's depth, at Cover.x where no hand was drawn

layout(push_constant) uniform HandCover {
    vec4 Cover;    // x the hand depth's clear value
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 size = textureSize(SceneSampler, 0);
    ivec2 corner = ivec2(gl_FragCoord.xy) * 4;
    float covered = 0.0;
    vec3 handGlow = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            ivec2 pixel = min(corner + ivec2(x, y), size - 1);
            if (texelFetch(HandDepthSampler, pixel, 0).r == Cover.x) {
                continue;
            }
            covered += 1.0;
            vec4 scene = texelFetch(SceneSampler, pixel, 0);
            handGlow += lite_srgb_to_linear(scene.rgb) * lite_emissive_from_alpha(scene.a, true);
        }
    }
    vec3 level = texelFetch(PrefilterSampler, ivec2(gl_FragCoord.xy), 0).rgb;
    fragColor = vec4(level * (1.0 - covered / 16.0) + handGlow / 16.0, 1.0);
}
