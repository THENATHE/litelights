#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:frame.glsl>
#include <litelights:water/uniforms.glsl>

// Underwater depth blur: a disc of samples whose size grows with distance, so nearby things stay
// sharp and the murk farther off goes soft. Samples farther than the centre pixel count fully;
// nearer ones count less, so a sharp object in front doesn't smear over the background.

uniform sampler2D BlurSource;
uniform sampler2D BlurDepth;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int TAPS = 12;
const vec2 DISC[TAPS] = vec2[TAPS](
    vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),
    vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893),
    vec2(0.507, 0.064), vec2(0.896, 0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598)
);

float viewDepth(float deviceDepth) {
    float denominator = deviceDepth - LiteWaterDepth.x;
    return denominator > 1.0e-7 ? LiteWaterDepth.y / denominator : 1.0e9;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 centre = texelFetch(BlurSource, pixel, 0);
    float depth = viewDepth(texelFetch(BlurDepth, pixel, 0).r);
    // Sharp within a couple of blocks, softest from about 16 blocks on (vanilla's underwater fog
    // hides most things beyond that); scaled with the screen height.
    float radius = smoothstep(2.0, 16.0, depth) * 6.0 * LiteScreen.y / 1080.0;
    if (radius < 0.5) {
        fragColor = centre;
        return;
    }
    vec2 texel = LiteScreen.zw;
    vec2 uv = gl_FragCoord.xy * texel;
    vec4 sum = centre;
    float weight = 1.0;
    for (int i = 0; i < TAPS; i++) {
        vec2 offset = DISC[i] * radius;
        ivec2 tapPixel = clamp(pixel + ivec2(offset), ivec2(0), ivec2(LiteScreen.xy) - 1);
        float tapDepth = viewDepth(texelFetch(BlurDepth, tapPixel, 0).r);
        float tapWeight = clamp(1.0 - (depth - tapDepth) / 4.0, 0.1, 1.0);
        sum += texture(BlurSource, uv + offset * texel) * tapWeight;
        weight += tapWeight;
    }
    fragColor = vec4((sum / weight).rgb, centre.a);
}
