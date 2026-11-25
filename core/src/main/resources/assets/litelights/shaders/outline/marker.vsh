#version 330
#extension GL_ARB_separate_shader_objects : require

// Enchantment outline markers (OutlineMarkers.java): an invisible copy of each outlined item or
// armour piece. In the game's own passes every corner is put outside the screen, so a marker
// touches no pixel. In the outline's mask (LITE_OUTLINE_MASK) it is drawn where the item is, in the
// colour its outline should glow, which rides in the vertex colour.

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;

void main() {
    #ifdef LITE_OUTLINE_MASK
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    #else
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    #endif
    vertexColor = Color;
    texCoord0 = UV0;
}
