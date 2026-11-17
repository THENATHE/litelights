#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
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

#if !defined(OIT_ALPHA_ONLY) && !defined(LITELIGHTS_SHADOW_CASTER)
uniform sampler2D Sampler2;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in vec3 cameraRelativePosition;
layout(location = 5) in vec2 lightCoord;

#if !defined(OIT_ALPHA_ONLY) && !defined(LITELIGHTS_SHADOW_CASTER)
layout(location = 0) out vec4 fragColor;
#endif

#ifdef LITELIGHTS_SHADOW_CASTER

void main() {
    if (texture(Sampler0, texCoord0).a < 0.5) {
        discard;
    }
}

#elif defined(LITE_TERRAIN_LITELIGHTS)

vec4 calculateFinalColor(vec4 color, LiteSurface surface) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    #ifdef LITE_TERRAIN_FOG
    if (lite_world_pass() && lite_fog(color, surface, fogColor)) {
        return color;
    }
    #endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    vec2 atlasTexels = vec2(textureSize(Sampler0, 0));
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    vec3 normal = lite_face_normal(cameraRelativePosition);
    #ifdef LITE_TERRAIN_SHADOWS
    vec3 shadowPosition = lite_shadow_position(cameraRelativePosition, normal, texCoord0, atlasTexels);
    #endif
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
    surface.material = lite_material(texCoord0);
    surface.kind = LITE_SURFACE_BLOCK_MODEL;
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
        #ifdef LITE_TERRAIN_LIGHT
        lite_light_surface(color, surface);
        #endif
    }
    #endif

    #ifdef OIT_ALPHA_ONLY
    #ifdef LITE_TERRAIN_WATER
    if (lite_world_pass()) {
        lite_water_oit_alpha(color, surface);
    }
    #endif
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color, surface);
    #ifndef OIT_ACCUMULATE
    lite_mark_near(fragColor);
    #endif
    #endif
}

#else

// Use vanilla terrain shading (Mac troubleshooting): vanilla's block-model fragment stage, with none
// of LiteLights' includes. The lightmap is looked up here because LiteLights' vertex stage hands on
// the light coordinate instead of the lightmap's colour.
void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    #ifndef OIT_ALPHA_ONLY
    color *= texture(Sampler2, clamp(lightCoord / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
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
