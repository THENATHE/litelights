#version 330
#extension GL_ARB_separate_shader_objects : require

// See marker.vsh. In the mask, the item's own cut-out decides its shape: transparent texels are not
// part of the silhouette. Mip 0, so the half-resolution mask doesn't fatten thin parts.

uniform sampler2D Sampler0;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    #ifdef LITE_OUTLINE_MASK
    if (textureLod(Sampler0, texCoord0, 0.0).a < 0.1) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, 1.0);
    #else
    discard;
    #endif
}
