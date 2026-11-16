#version 330
#extension GL_ARB_separate_shader_objects : require

// First half of the first-person enchantment outline's glow: reads the silhouette of the enchanted
// items in hand (the markers' depth, cleared where none was drawn) and blurs it along one direction.

uniform sampler2D MaskSampler;

layout(push_constant) uniform MaskBlur {
    vec4 Step;    // xy one blur step in texture units; z the mask's clear depth
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

float coverage(vec2 uv) {
    return texture(MaskSampler, uv).r != Step.z ? 1.0 : 0.0;
}

void main() {
    const float weights[5] = float[5](0.2042, 0.1802, 0.1238, 0.0663, 0.0276);
    float sum = coverage(texCoord) * weights[0];
    for (int i = 1; i < 5; i++) {
        vec2 offset = Step.xy * float(i);
        sum += (coverage(texCoord + offset) + coverage(texCoord - offset)) * weights[i];
    }
    fragColor = vec4(sum, sum, sum, 1.0);
}
