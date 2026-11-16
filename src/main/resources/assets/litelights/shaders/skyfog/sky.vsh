#version 330
#extension GL_ARB_separate_shader_objects : require

// One triangle that covers the screen. The clip-space position is passed on, so the fragment
// shader can turn it back into a view direction whatever way the graphics API flips the screen.

layout(location = 0) out vec2 clipPosition;

void main() {
    vec2 corner = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    clipPosition = corner * 2.0 - 1.0;
    gl_Position = vec4(clipPosition, 0.0, 1.0);
}
