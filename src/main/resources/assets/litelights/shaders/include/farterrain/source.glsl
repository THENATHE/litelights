#ifndef LITELIGHTS_FARTERRAIN_SOURCE_GLSL
#define LITELIGHTS_FARTERRAIN_SOURCE_GLSL

// Where the far-terrain passes read their pixels from (render.feature.farterrain).
//
// By default that is the main target just before translucent terrain: LiteLights' own surfaces,
// already shaded and fogged, and Voxy's far terrain, which Voxy writes into the main target's
// colour and depth with the level's own projection. With LITE_FAR_SOURCE_DH it is Distant
// Horizons' colour and depth textures, before Distant Horizons copies them into the main target;
// their depth uses Distant Horizons' projection, which the pass is given.

#include <litelights:post_common.glsl>

layout(push_constant) uniform FarTerrainValues {
    mat4 FarInverseProjection;  // Distant Horizons: its clip space -> camera-relative position (unused otherwise)
    vec4 FarDepthInfo;          // x 1 when depth is reversed (far = 0), y 1 when depth is 0..1, zw one far shadow texel in texture units
    vec4 FarSwitches;           // x 1 when the pixels include another mod's far terrain, y relight it, z far shadows (1 on), w far water
    vec4 FarShadowRange;        // x, y where far shadows fade in (horizontal blocks), z camera-relative height of the sea surface, w 1 to fog far terrain
    vec4 FarWaterSwitches;      // x 1 when far water was captured from the far mod's translucent pass this frame, y far caustics, z far depth colour, w far edge foam (the far-shadow passes: the player, see far_shadow.fsh)
};

bool lite_far_has_depth(float depth) {
    #ifdef LITE_FAR_SOURCE_DH
    return FarDepthInfo.x > 0.5 ? depth > 0.0 : depth < 1.0;
    #else
    return depth > 0.0;
    #endif
}

// Camera-relative position of a pixel from its texture coordinate and depth.
vec3 lite_far_position(vec2 uv, float depth) {
    #ifdef LITE_FAR_SOURCE_DH
    float ndcZ = FarDepthInfo.y > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 position = FarInverseProjection * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    return position.xyz / position.w;
    #else
    return lite_position_from_depth(uv, depth);
    #endif
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
