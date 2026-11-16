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
#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:shadow.glsl>
#include <litelights:lighting.glsl>
// The water hook reads the block atlas through this shader's own sampler instead of one of its
// own: Sodium's translucent terrain under Improved Transparency would otherwise need 13 texture
// units, one more than OpenGL gives a draw (docs/ARCHITECTURE.md, "Texture units").
uniform sampler2D Sampler0;
#define LITE_HOST_BLOCK_ATLAS Sampler0
#include <litelights:hooks.glsl>

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

vec4 calculateFinalColor(vec4 color, LiteSurface surface, float visibility) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    if (lite_world_pass() && lite_fog(color, surface, fogColor)) {
        // A newly loaded chunk fades in from the colour LiteLights' fog fades the land into.
        color.rgb = mix(lite_fade_in_color(color, surface, fogColor), color.rgb, visibility);
        return color;
    }
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    vec2 atlasTexels = vec2(TextureSize);
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    vec3 normal = lite_face_normal(cameraRelativePosition);
    vec3 shadowPosition = lite_shadow_position(cameraRelativePosition, normal, texCoord0, atlasTexels);
    int material = lite_material(texCoord0);
    lite_glass_surface(color, cameraRelativePosition, normal, texCoord0, lightCoord, material);

    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
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
    surface.shadow = lite_world_pass() ? lite_shadow_visibility(shadowPosition, normal) : 1.0;

    #ifndef OIT_ALPHA_ONLY
    color *= lite_world_light(Sampler2, lightCoord, surface.shadow, surface.facing);
    if (lite_world_pass()) {
        lite_water_surface(color, surface);
        lite_light_surface(color, surface);
    }
    if (!lite_fog_replaces_vanilla()) {
        color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    }
    #endif

    #ifdef OIT_ALPHA_ONLY
    if (lite_world_pass()) {
        lite_water_oit_alpha(color, surface);
    }
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color, surface, chunkVisibility);
    #ifndef OIT_ACCUMULATE
    lite_mark_near(fragColor);
    #endif
    #endif
}
