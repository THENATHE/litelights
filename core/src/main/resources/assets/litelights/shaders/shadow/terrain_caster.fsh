#version 330
#extension GL_ARB_separate_shader_objects : require

// Depth only. Cutout casters (leaves, plants) are alpha-tested against the block atlas so their
// holes let light through; merged solid faces skip the texture read.

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) flat in uint alphaTested;

void main() {
    if (alphaTested != 0u && textureLod(Sampler0, texCoord0, 0.0).a < 0.5) {
        discard;
    }
}
