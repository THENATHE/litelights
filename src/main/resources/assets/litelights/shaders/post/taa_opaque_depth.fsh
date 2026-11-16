#version 330
#extension GL_ARB_separate_shader_objects : require

// Copies the opaque world's depth for TAA (TaaOpaqueDepth), before translucent surfaces draw over it.
// Reversed depth, as the main target holds it: 0 is the sky, nearer is larger.

uniform sampler2D InDepth;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(texelFetch(InDepth, ivec2(gl_FragCoord.xy), 0).r);
}
