#version 330
#extension GL_ARB_separate_shader_objects : require

// One triangle that covers the whole target. texCoord runs 0..1 across the screen with the same
// orientation the samplers use, so texture(InSampler, texCoord) reads the pixel being drawn.

layout(location = 0) out vec2 texCoord;

void main() {
    vec2 corner = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
    texCoord = corner;
}
