#version 330
#extension GL_ARB_separate_shader_objects : require

// One direction of a separable 9-texel Gaussian, in five bilinear taps (the offsets and weights
// fold neighbouring texel pairs into single filtered reads).

uniform sampler2D InputSampler;

layout(push_constant) uniform Blur {
    vec4 Step;    // xy one texel along the blur direction, in texture units (scaled to widen it)
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    const float weightCentre = 0.2270270270;
    const float weightNear = 0.3162162162;
    const float weightFar = 0.0702702703;
    vec2 near = Step.xy * 1.3846153846;
    vec2 far = Step.xy * 3.2307692308;
    vec4 sum = texture(InputSampler, texCoord) * weightCentre
        + (texture(InputSampler, texCoord + near) + texture(InputSampler, texCoord - near)) * weightNear
        + (texture(InputSampler, texCoord + far) + texture(InputSampler, texCoord - far)) * weightFar;
    fragColor = sum;
}
