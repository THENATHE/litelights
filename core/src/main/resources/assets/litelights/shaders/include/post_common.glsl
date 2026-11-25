#ifndef LITELIGHTS_POST_COMMON_GLSL
#define LITELIGHTS_POST_COMMON_GLSL

#include <litelights:frame.glsl>
#include <litelights:colour.glsl>
#include <litelights:emissive.glsl>

// Helpers shared by the post passes. Colour conversions are in colour.glsl, the bloom mask in
// emissive.glsl; both come with this file.

// Camera-relative position of a pixel from its texture coordinate and reversed depth.
vec3 lite_position_from_depth(vec2 uv, float depth) {
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float ndcZ = depth;
#else
    float ndcZ = depth * 2.0 - 1.0;
#endif
    vec4 clip = vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    vec4 position = LiteInverseViewProjection * clip;
    return position.xyz / position.w;
}

// Unit view direction through a pixel, camera-relative. Taken between two points on the pixel's line
// of sight, not from the camera position: with view bobbing the eye is off the camera position, and
// a point near the camera (the near plane is 0.05 blocks away) would make the direction swing with
// every step.
vec3 lite_view_direction(vec2 uv) {
    vec3 near = lite_position_from_depth(uv, 1.0);
    vec3 far = lite_position_from_depth(uv, 0.001);
    return normalize(far - near);
}

#endif
