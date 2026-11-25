#version 330
#extension GL_ARB_separate_shader_objects : require

// Section shadow casters into the sun shadow map. Positions arrive packed (see CasterMesh); the
// push constant holds the section's whole transform, packing scale included.

layout(location = 0) in uvec4 Position;
layout(location = 1) in vec2 UV0;

layout(push_constant) uniform CasterTransform {
    mat4 SectionToClip;
};

layout(location = 0) out vec2 texCoord0;
layout(location = 1) flat out uint alphaTested;

void main() {
    gl_Position = SectionToClip * vec4(vec3(Position.xyz), 1.0);
    texCoord0 = UV0;
    alphaTested = Position.w & 1u;
}
