#version 330
#extension GL_ARB_separate_shader_objects : require

// How far the opaque scene is from the eye at each pixel, in blocks, for the haze puffs and the
// classic haze billboards (particle.HazeDepth). The sky gets a distance past anything they reach.

#include <litelights:post_common.glsl>

uniform sampler2D InDepth;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float SKY_DISTANCE = 65000.0;

void main() {
    float depth = texelFetch(InDepth, ivec2(gl_FragCoord.xy), 0).r;
    // Reversed depth: the sky is 0.
    float distance = SKY_DISTANCE;
    if (depth > 0.0) {
        distance = min(length(lite_position_from_depth(texCoord, depth) - lite_view_origin()), SKY_DISTANCE);
    }
    fragColor = vec4(distance, 0.0, 0.0, 1.0);
}
