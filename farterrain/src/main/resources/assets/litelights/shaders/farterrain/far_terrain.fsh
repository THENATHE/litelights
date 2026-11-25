#version 330
#extension GL_ARB_separate_shader_objects : require

// The far-terrain pass (render.feature.farterrain.FarTerrainPass). Reads a copy of the target and
// its depth, and puts far shadows on LiteLights' own terrain past the shadow map, under the fog it
// already has. Every other pixel is discarded. The main target's alpha (the emissive mask) is
// written back as it was.

#include <litelights:farterrain/far_light.glsl>

uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D LightmapSampler;
// Far shadows at half resolution: r visibility, g distance from the camera in blocks (for the
// depth-aware upsample).
uniform sampler2D FarShadowSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// The far shadow at a pixel, from the four half-resolution texels around it. Each weighs in by
// its bilinear share and by how close its distance is to the pixel's, so a shadow never bleeds
// across a silhouette onto land far behind it.
float lite_far_shadow_at(vec2 uv, float distance) {
    vec2 texel = uv / FarDepthInfo.zw - 0.5;
    ivec2 base = ivec2(floor(texel));
    vec2 blend = texel - vec2(base);
    ivec2 last = textureSize(FarShadowSampler, 0) - 1;
    float total = 0.0;
    float weights = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        vec2 stored = texelFetch(FarShadowSampler, clamp(base + offset, ivec2(0), last), 0).rg;
        vec2 share = mix(1.0 - blend, blend, vec2(offset));
        float closeness = 1.0 / (abs(stored.g - distance) + 0.02 * distance + 0.5);
        float weight = share.x * share.y * closeness;
        total += stored.r * weight;
        weights += weight;
    }
    return weights > 0.0 ? total / weights : 1.0;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    if (!lite_far_has_depth(depth)) {
        discard;
    }
    vec3 position = lite_far_position(texCoord, depth);
    float shadowWeight = smoothstep(FarShadowRange.x, FarShadowRange.y, length(position.xz));
    if (shadowWeight <= 0.0) {
        discard;
    }
    vec4 scene = texelFetch(SceneColor, pixel, 0);
    vec3 normal = lite_far_normal(SceneDepth, pixel, position);
    float facing = lite_light_facing(normal);
    float farVisibility = lite_far_shadow_at(texCoord, length(position));
    fragColor = vec4(lite_far_shadow_own(scene.rgb, LightmapSampler, position, farVisibility, shadowWeight, facing), scene.a);
}
