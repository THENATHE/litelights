#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:post_common.glsl>

// First bloom step: the level, down to a quarter of the screen in each direction, keeping only
// emissive pixels (the mask in alpha, from glowing, lava and fire materials) and the very
// brightest pixels of the sky (the sun and moon). Each output texel reads its 4x4 block of screen pixels one by one:
// filtered reads would average marked and unmarked alpha and lose small lights like torches.
//
// Water drawn over lava replaces the lava's mark with its own alpha. Water's copy of the opaque
// scene (taken before translucent terrain) still has the lava's colour and mark; the alpha now in
// the scene says how much of it shows through. Translucent surfaces blend their alpha "over", so
// with the opaque alpha a0 and the alpha now a, the share still visible is (1 - a) / (1 - a0),
// through any number of layers. That share of the lava's own colour is the part that glows.

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D OpaqueColorSampler;   // water's scene copy: rgb is the opaque colour
uniform sampler2D OpaqueAlphaSampler;   // the opaque scene's alpha (1 for the sky)

layout(push_constant) uniform BloomPrefilter {
    vec4 Source;    // x 1 when the opaque copy exists this frame, y unused, z luminance threshold,
                    // w weight of merely bright pixels
};

// The glow of an emissive pixel that something translucent now covers, or 0.
vec3 lite_glow_behind(ivec2 pixel, float alphaNow) {
    float opaqueAlpha = texelFetch(OpaqueAlphaSampler, pixel, 0).r;
    float emissive = lite_emissive_from_alpha(opaqueAlpha, true);
    if (emissive <= 0.0) {
        return vec3(0.0);
    }
    float shownThrough = clamp((1.0 - alphaNow) / (1.0 - opaqueAlpha), 0.0, 1.0);
    return lite_srgb_to_linear(texelFetch(OpaqueColorSampler, pixel, 0).rgb) * emissive * shownThrough;
}

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 size = textureSize(SceneSampler, 0);
    ivec2 corner = ivec2(gl_FragCoord.xy) * 4;
    vec3 sum = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            ivec2 pixel = min(corner + ivec2(x, y), size - 1);
            vec4 scene = texelFetch(SceneSampler, pixel, 0);
            bool hasDepth = texelFetch(DepthSampler, pixel, 0).r > 0.0;
            float emissive = lite_emissive_from_alpha(scene.a, hasDepth);
            // Only the sky's brightest pixels (the sun and moon) glow without a mask, so sunlit snow
            // and white wool don't. Luminance, not the brightest channel: a pale blue sky has blue
            // near 1 but isn't a light.
            float bright = hasDepth ? 0.0 : smoothstep(Source.z, 1.0, lite_luma(scene.rgb)) * Source.w;
            if (emissive <= 0.0 && hasDepth && Source.x > 0.5) {
                sum += lite_glow_behind(pixel, scene.a);
            }
            sum += lite_srgb_to_linear(scene.rgb) * max(emissive, bright);
        }
    }
    fragColor = vec4(sum / 16.0, 1.0);
}
