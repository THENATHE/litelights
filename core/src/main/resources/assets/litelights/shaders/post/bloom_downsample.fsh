#version 330
#extension GL_ARB_separate_shader_objects : require

// Halves a bloom level: four bilinear taps average the 4x4 source texels under each output texel.

uniform sampler2D InputSampler;

layout(push_constant) uniform Downsample {
    vec4 Source;    // xy one source texel in texture units
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 texel = Source.xy;
    vec3 sum = texture(InputSampler, texCoord + vec2(-texel.x, -texel.y)).rgb
        + texture(InputSampler, texCoord + vec2(texel.x, -texel.y)).rgb
        + texture(InputSampler, texCoord + vec2(-texel.x, texel.y)).rgb
        + texture(InputSampler, texCoord + vec2(texel.x, texel.y)).rgb;
    fragColor = vec4(sum * 0.25, 1.0);
}
