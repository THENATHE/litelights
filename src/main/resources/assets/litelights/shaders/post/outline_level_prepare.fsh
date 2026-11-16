#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:post_common.glsl>

// First step of the glow around outlined things in the level (worn armour, items in hand): for each
// mask pixel, whether it counts (a marker was drawn there, the level doesn't hide it, it is within
// reach) and how far its glow spreads. The spread is the first-person one scaled to how big the item
// shows from this far away, never wider, never thinner than a pixel or so: OutlineScale.java does the
// same sums, change both together. Depth is reversed here, like the level's: larger is nearer.

uniform sampler2D MaskSampler;         // the markers' depth, at Values.x where none was drawn
uniform sampler2D SceneDepthSampler;   // the level's depth

layout(push_constant) uniform Prepare {
    vec4 Values;    // x the mask's clear depth, y reach in blocks, z first-person sigma in mask pixels, w reference distance in blocks
    vec4 Limits;    // x the thinnest sigma in mask pixels
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    float mask = texture(MaskSampler, texCoord).r;
    // Shown unless the level has something clearly nearer in front of it.
    if (mask == Values.x || mask < texture(SceneDepthSampler, texCoord).r * 0.98) {
        fragColor = vec4(0.0);
        return;
    }
    float distance = length(lite_position_from_depth(texCoord, mask));
    if (distance > Values.y) {
        fragColor = vec4(0.0);
        return;
    }
    float scale = distance <= Values.w ? 1.0 : Values.w / distance;
    float sigma = max(Values.z * scale, min(Limits.x, Values.z));
    fragColor = vec4(1.0, sigma, 0.0, 1.0);
}
