#ifndef LITELIGHTS_FARTERRAIN_SOURCE_GLSL
#define LITELIGHTS_FARTERRAIN_SOURCE_GLSL

// What the far-terrain passes read their pixels from (render.feature.farterrain): the main target
// just before translucent terrain, LiteLights' own surfaces already shaded and fogged.

#include <litelights:post_common.glsl>

layout(push_constant) uniform FarTerrainValues {
    vec4 FarDepthInfo;          // zw one far shadow texel in texture units; xy unused
    vec4 FarSwitches;           // the far-shadow passes: x the heightfield's floor (camera-relative y), y how far round the camera it reaches, z how far Advanced marches; unused in the terrain pass
    vec4 FarShadowRange;        // x, y where far shadows fade in (horizontal blocks), z camera-relative height of the sea surface
    vec4 FarPlayer;             // the far-shadow passes: xyz the middle of the player's body, w how far round it the trace looks past (0: nobody)
};

bool lite_far_has_depth(float depth) {
    return depth > 0.0;
}

// Camera-relative position of a pixel from its texture coordinate and depth.
vec3 lite_far_position(vec2 uv, float depth) {
    return lite_position_from_depth(uv, depth);
}

// The face normal at a pixel from the depths around it, turned toward the camera. Each axis takes
// the neighbour on the side whose surface continues the pixel's best (the nearer one in depth), so
// silhouettes don't bend the normal.
vec3 lite_far_normal(sampler2D depthTexture, ivec2 pixel, vec3 position) {
    ivec2 last = textureSize(depthTexture, 0) - 1;
    vec2 size = vec2(last + 1);
    ivec2 right = min(pixel + ivec2(1, 0), last);
    ivec2 left = max(pixel - ivec2(1, 0), ivec2(0));
    ivec2 up = min(pixel + ivec2(0, 1), last);
    ivec2 down = max(pixel - ivec2(0, 1), ivec2(0));
    vec3 toRight = lite_far_position((vec2(right) + 0.5) / size, texelFetch(depthTexture, right, 0).r) - position;
    vec3 toLeft = position - lite_far_position((vec2(left) + 0.5) / size, texelFetch(depthTexture, left, 0).r);
    vec3 toUp = lite_far_position((vec2(up) + 0.5) / size, texelFetch(depthTexture, up, 0).r) - position;
    vec3 toDown = position - lite_far_position((vec2(down) + 0.5) / size, texelFetch(depthTexture, down, 0).r);
    vec3 alongX = dot(toRight, toRight) < dot(toLeft, toLeft) ? toRight : toLeft;
    vec3 alongY = dot(toUp, toUp) < dot(toDown, toDown) ? toUp : toDown;
    vec3 normal = normalize(cross(alongX, alongY));
    return dot(normal, position) > 0.0 ? -normal : normal;
}

#endif
