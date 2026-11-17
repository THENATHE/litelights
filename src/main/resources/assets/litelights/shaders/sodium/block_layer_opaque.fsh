#version 460 core

#include <sodium:globals.glsl>
#include <sodium:fog.glsl>
#include <sodium:chunk_material.glsl>
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
uniform sampler2D u_BlockTex;
#ifdef LITE_TERRAIN_LITELIGHTS
#define LITE_HOST_BLOCK_ATLAS u_BlockTex
#include <litelights:hooks.glsl>
#endif

layout(location = 0) in vec4 lite_vertexColor;
layout(location = 1) in vec2 lite_atlasUv;
#ifdef USE_FOG
layout(location = 2) in vec2 lite_fogDistance;
layout(location = 3) in float lite_fadeIn;
#endif
layout(location = 4) in vec3 lite_cameraRelative;
layout(location = 5) in vec2 lite_lightCoord;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D u_LightTex;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

#ifdef LITE_TERRAIN_LITELIGHTS
// LiteLights' fog where it replaces vanilla's, otherwise vanilla's fog through Sodium's include.
vec4 lite_apply_fog(vec4 color, LiteSurface surface) {
    vec4 fogColor = u_FogColor;
    #ifdef OIT_ACCUMULATE
        color = sampleColorForAccumulation(color);
        // The accumulation stage works in premultiplied colour; so must the fog.
        fogColor.rgb *= color.a;
    #endif

    #ifdef LITE_TERRAIN_FOG
    if (lite_world_pass() && lite_fog(color, surface, fogColor)) {
        #ifdef USE_FOG
        // Sodium fades newly loaded sections in inside its fog function, which LiteLights' fog
        // replaces. lite_fadeIn is Sodium's own fade value (from its section times and fade
        // period), applied here on top of LiteLights' fog, from the colour the land fades into.
        color.rgb = mix(lite_fade_in_color(color, surface, fogColor), color.rgb, lite_fadeIn);
        #endif
        return color;
    }
    #endif
    #ifdef USE_FOG
    color = _linearFog(color, lite_fogDistance, fogColor, u_EnvironmentFog, u_RenderFog, lite_fadeIn);
    #endif
    return color;
}
#endif

// Reads the block atlas the way Sodium's chunks look. Each texel stays flat, and its edges are
// anti-aliased over one screen pixel: the lookup is pulled to the nearest texel edge and only
// crosses it within half a pixel, where the sampler's bilinear filter blends the two texels. With
// Sodium's RGSS setting on, surfaces far enough away that a pixel spans more than a texel fade into
// the average of four rotated-grid taps, so distant pixel art doesn't shimmer.
vec4 lite_sample_atlas(sampler2D atlas, vec2 uv, vec2 texelSize, bool supersample) {
    vec2 alongX = dFdx(uv);
    vec2 alongY = dFdy(uv);
    // How far across the atlas one screen pixel reaches, in u and in v.
    vec2 pixelReach = sqrt(alongX * alongX + alongY * alongY);

    vec2 inTexels = uv / texelSize;
    vec2 nearestEdge = floor(inTexels + 0.5);
    vec2 pixelsPastEdge = (inTexels - nearestEdge) * texelSize / max(pixelReach, vec2(1.0e-9));
    vec2 crisp = (nearestEdge + clamp(pixelsPastEdge, -0.5, 0.5)) * texelSize;
    vec4 color = textureGrad(atlas, crisp, alongX, alongY);
    if (!supersample) {
        return color;
    }

    float smallestTexel = min(texelSize.x, texelSize.y);
    float texelsPerPixel = max(pixelReach.x, pixelReach.y) / smallestTexel;
    // The mip level of the geometric mean of the two screen axes.
    float level = max(0.0, 0.5 * log2(length(alongX) * length(alongY)) - log2(smallestTexel));
    vec4 average = 0.25 * (
        textureLod(atlas, uv + vec2(0.125, 0.375) * texelSize, level)
        + textureLod(atlas, uv + vec2(0.375, -0.125) * texelSize, level)
        + textureLod(atlas, uv + vec2(-0.125, -0.375) * texelSize, level)
        + textureLod(atlas, uv + vec2(-0.375, 0.125) * texelSize, level));
    return mix(color, average, smoothstep(1.0, 2.0, texelsPerPixel));
}

#ifdef LITE_TERRAIN_LITELIGHTS

void main() {
    vec4 color = lite_sample_atlas(u_BlockTex, lite_atlasUv, u_TexelSize, u_UseRGSS) * lite_vertexColor;
    vec2 atlasTexels = 1.0 / u_TexelSize;
    vec3 normal = lite_face_normal(lite_cameraRelative);
    #ifdef LITE_TERRAIN_SHADOWS
    vec3 shadowPosition = lite_shadow_position(lite_cameraRelative, normal, lite_atlasUv, atlasTexels);
    #endif
    int material = lite_material(lite_atlasUv);
    #ifdef LITE_TERRAIN_GLASS
    lite_glass_surface(color, lite_cameraRelative, normal, lite_atlasUv, lite_lightCoord, material);
    #endif

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    #if defined(LITE_TERRAIN_CAUSTICS) && !defined(OIT_ALPHA_ONLY)
    lite_ground_leaves(color, lite_vertexColor, lite_cameraRelative, normal, lite_lightCoord);
    lite_snow_cover(color, lite_vertexColor, lite_cameraRelative, normal, lite_lightCoord, material);
    #endif

    LiteSurface surface;
    surface.position = lite_cameraRelative;
    surface.normal = normal;
    surface.uv = lite_atlasUv;
    surface.textureTexels = atlasTexels;
    surface.lightCoord = lite_lightCoord;
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
    color *= lite_world_light(u_LightTex, lite_lightCoord, surface.shadow, surface.facing);
    #else
    color *= lite_sample_lightmap(u_LightTex, lite_lightCoord);
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
    #endif

    #ifdef OIT_ALPHA_ONLY
    #ifdef LITE_TERRAIN_WATER
    if (lite_world_pass()) {
        lite_water_oit_alpha(color, surface);
    }
    #endif
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = lite_apply_fog(color, surface);
    #ifndef OIT_ACCUMULATE
    lite_mark_near(fragColor);
    #endif
    #endif
}

#else

// Use vanilla terrain shading (Mac troubleshooting): Sodium's own shading, with none of LiteLights'
// includes. The lightmap is looked up here because LiteLights' vertex stage hands on the light
// coordinate instead of the lightmap's colour (in vanilla's units, as Sodium's half texel is taken out).
void main() {
    vec4 color = lite_sample_atlas(u_BlockTex, lite_atlasUv, u_TexelSize, u_UseRGSS) * lite_vertexColor;
    #ifndef OIT_ALPHA_ONLY
    color *= texture(u_LightTex, clamp(lite_lightCoord / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    #endif

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    vec4 fogColor = u_FogColor;
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    fogColor.rgb *= color.a;
    #endif
    #ifdef USE_FOG
    color = _linearFog(color, lite_fogDistance, fogColor, u_EnvironmentFog, u_RenderFog, lite_fadeIn);
    #endif
    fragColor = color;
    #endif
}

#endif
