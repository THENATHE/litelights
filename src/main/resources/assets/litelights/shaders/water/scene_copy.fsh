#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:frame.glsl>
#include <litelights:water/uniforms.glsl>

// Copies the opaque scene for the water shaders into one texture: the colour, and the view depth in
// blocks in alpha (one sampler instead of two, and depths the reflection march can compare directly).
// The second target keeps the opaque scene's own alpha, light-post's emissive mask, for bloom; the
// sky gets 1 there, since its cleared alpha would otherwise read as a glowing pixel.

uniform sampler2D InColor;
uniform sampler2D InDepth;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 opaqueAlpha;

const float SKY_DEPTH = 65000.0;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float deviceDepth = texelFetch(InDepth, pixel, 0).r;
    // Reversed depth: the sky is 0, nearer is larger. Device depth = x + y / view depth.
    float denominator = deviceDepth - LiteWaterDepth.x;
    float viewDepth = denominator > 1.0e-7 ? min(LiteWaterDepth.y / denominator, SKY_DEPTH) : SKY_DEPTH;
    vec4 color = texelFetch(InColor, pixel, 0);
    fragColor = vec4(color.rgb, viewDepth);
    opaqueAlpha = vec4(deviceDepth > 0.0 ? color.a : 1.0);
}
