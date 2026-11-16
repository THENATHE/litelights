#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:light_post/rays_composite.glsl>

// Adds the light rays (drawn at a quarter, half or full resolution) to the level. The pipeline screen-blends the result
// (source times one minus the destination, plus the destination), so rays never push past white.

uniform sampler2D RaysSampler;
uniform sampler2D DepthSampler;

layout(push_constant) uniform Composite {
    vec4 Source;    // xy one rays texel in texture units, z 1 to show air rays only along shaded view lines
    vec4 Light;     // rgb colour of the scattered light
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(lite_rays_to_add(RaysSampler, DepthSampler, texCoord, Source.xy, Light.rgb, Source.z > 0.5), 0.0);
}
