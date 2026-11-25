#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif
#include <litelights:terrain_parts.glsl>
#ifdef LITE_TERRAIN_LITELIGHTS
#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:shadow.glsl>
#include <litelights:lighting.glsl>
#endif
// The water hook reads the block atlas through this shader's own sampler instead of one of its
// own: Sodium's translucent terrain under Improved Transparency would otherwise need 13 texture
// units, one more than OpenGL gives a draw (docs/ARCHITECTURE.md, "Texture units").
uniform sampler2D Sampler0;
#ifdef LITE_TERRAIN_LITELIGHTS
#define LITE_HOST_BLOCK_ATLAS Sampler0
#include <litelights:hooks.glsl>
#endif

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;
layout(location = 5) in vec3 cameraRelativePosition;
layout(location = 6) in vec2 lightCoord;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

#ifdef LITE_TERRAIN_LITELIGHTS

vec4 calculateFinalColor(vec4 color, LiteSurface surface, float visibility) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    #ifdef LITE_TERRAIN_FOG
    if (lite_world_pass() && lite_fog(color, surface, fogColor)) {
        // A newly loaded chunk fades in from the colour LiteLights' fog fades the land into.
        color.rgb = mix(lite_fade_in_color(color, surface, fogColor), color.rgb, visibility);
        return color;
    }
    #endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    vec2 atlasTexels = vec2(TextureSize);
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    vec3 normal = lite_face_normal(cameraRelativePosition);
    #ifdef LITE_TERRAIN_SHADOWS
    vec3 shadowPosition = lite_shadow_position(cameraRelativePosition, normal, texCoord0, atlasTexels);
    #endif
    int material = lite_material(texCoord0);
    #ifdef LITE_TERRAIN_GLASS
    lite_glass_surface(color, cameraRelativePosition, normal, texCoord0, lightCoord, material);
    #endif

    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif
    #if defined(LITE_TERRAIN_CAUSTICS) && !defined(OIT_ALPHA_ONLY)
    lite_ground_leaves(color, vertexColor, cameraRelativePosition, normal, lightCoord);
    lite_snow_cover(color, vertexColor, cameraRelativePosition, normal, lightCoord, material);
    #endif

    LiteSurface surface;
    surface.position = cameraRelativePosition;
    surface.normal = normal;
    surface.uv = texCoord0;
    surface.textureTexels = atlasTexels;
    surface.lightCoord = lightCoord;
    surface.material = material;
    surface.kind = LITE_SURFACE_TERRAIN;
    surface.facing = lite_material_is_foliage(surface.material) ? 1.0 : lite_light_facing(normal);
    #ifdef LITE_TERRAIN_SHADOWS
    surface.shadow = lite_world_pass() ? lite_shadow_visibility(shadowPosition, normal) : 1.0;
    #else
    surface.shadow = 1.0;
    #endif

    #ifndef OIT_ALPHA_ONLY
    #ifdef LITE_TERRAIN_SHADOWS
    color *= lite_world_light(Sampler2, lightCoord, surface.shadow, surface.facing);
    #else
    color *= lite_sample_lightmap(Sampler2, lightCoord);
    #endif
    if (lite_world_pass()) {
        lite_water_surface(color, surface);
        #ifdef LITE_TERRAIN_CAUSTICS
        lite_puddles(color, surface);
        #endif
        #ifdef LITE_TERRAIN_LIGHT
        lite_light_surface(color, surface);
        #endif
    }
    #ifdef LITE_TERRAIN_FOG
    if (!lite_fog_replaces_vanilla()) {
        color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    }
    #else
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    #endif
    #endif

    #ifdef OIT_ALPHA_ONLY
    #ifdef LITE_TERRAIN_WATER
    if (lite_world_pass()) {
        lite_water_oit_alpha(color, surface);
    }
    #endif
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color, surface, chunkVisibility);
    #ifndef OIT_ACCUMULATE
    lite_hand_alpha(fragColor);
    #endif
    #endif
}

#else

// Use vanilla terrain shading (Mac troubleshooting): vanilla's terrain fragment stage, with none of
// LiteLights' includes. The lightmap is looked up here because LiteLights' vertex stage hands on
// the light coordinate instead of the lightmap's colour.
void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    #ifndef OIT_ALPHA_ONLY
    color *= texture(Sampler2, clamp(lightCoord / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    #endif
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
    #endif
}

#endif
