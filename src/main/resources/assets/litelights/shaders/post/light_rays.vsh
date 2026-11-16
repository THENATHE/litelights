#version 330
#extension GL_ARB_separate_shader_objects : require

// Light-ray slices: the full-screen triangle drawn once per slice (instanced). Each instance is a
// camera-facing billboard at its own distance; the fragment shader places it in the world.

layout(location = 0) out vec2 texCoord;
layout(location = 1) flat out int slice;

void main() {
    vec2 corner = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
    texCoord = corner;
    slice = gl_InstanceIndex;
}
