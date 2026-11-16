#version 330
#extension GL_ARB_separate_shader_objects : require

#ifdef GLINT
#include <minecraft:globals.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <litelights:frame.glsl>
#include <litelights:shadow.glsl>
#include <litelights:lighting.glsl>
#include <litelights:hooks.glsl>

uniform sampler2D Sampler0;

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY) && !defined(LITELIGHTS_SHADOW_CASTER)
uniform sampler2D Sampler2;
#endif

#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

#if defined(GLINT) && !defined(LITELIGHTS_SHADOW_CASTER)
uniform sampler2D GlintSampler;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
#else
layout(location = 2) in vec4 vertexColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) in vec4 overlayColor;
#endif

layout(location = 6) in vec2 texCoord0;
#ifdef GLINT
layout(location = 7) in vec2 texCoordGlint;
#endif
layout(location = 8) in vec3 cameraRelativePosition;
layout(location = 9) in vec2 lightCoord;
layout(location = 10) in vec3 worldNormal;

#if !defined(OIT_ALPHA_ONLY) && !defined(LITELIGHTS_SHADOW_CASTER)
layout(location = 0) out vec4 fragColor;
#endif

#ifdef LITELIGHTS_SHADOW_CASTER

// Depth only, into the mob shadow map. Texture alpha decides what casts, as in the colour pass.
void main() {
    float alpha = texture(Sampler0, texCoord0).a;
    if (alpha < 0.5) {
        discard;
    }
}

#else

#ifndef OIT_ALPHA_ONLY
vec4 calculateFinalColor(vec4 color, LiteSurface surface) {
    #ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    #endif

    #ifndef EMISSIVE
    color *= lite_world_light(Sampler2, lightCoord, surface.shadow, surface.facing);
    #endif

    if (lite_world_pass()) {
        lite_light_surface(color, surface);
    }

    #ifdef GLINT
    vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);
    // Matches BlendFuntion.GLINT
    color.rgb += glintColor.rgb * glintColor.rgb;
    #endif

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    if (lite_world_pass() && lite_fog(color, surface, fogColor)) {
        return color;
    }
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    vec3 normal = normalize(worldNormal);
    if (!gl_FrontFacing) {
        normal = -normal;
    }
    vec3 shadowPosition = lite_shadow_position(cameraRelativePosition, normal, texCoord0, vec2(textureSize(Sampler0, 0)));

    #ifdef OIT_ADDITIVE
    color.a = min(0.99, color.a);
    #endif

    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef PER_FACE_LIGHTING
    vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
    #else
    vec4 faceVertexColor = vertexColor;
    #endif

    #ifdef DISSOLVE
    if (faceVertexColor.a < texture(DissolveMaskSampler, texCoord0).a) {
        discard;
    }
    // The dissolve effect entirely replaces translucency
    faceVertexColor.a = 1.0;
    #endif

    color *= faceVertexColor * ColorModulator;

    #ifdef GLINT
    color.a = max(color.a, GlintAlpha);
    #endif

    LiteSurface surface;
    surface.position = cameraRelativePosition;
    surface.normal = normal;
    surface.uv = texCoord0;
    surface.textureTexels = vec2(textureSize(Sampler0, 0));
    surface.lightCoord = lightCoord;
    surface.material = 0;
    surface.kind = LITE_SURFACE_ENTITY;
    surface.facing = lite_light_facing(normal);
    surface.shadow = lite_world_pass() ? lite_shadow_visibility(shadowPosition, normal) : 1.0;

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color, surface);
    #ifndef OIT_ACCUMULATE
    lite_mark_near(fragColor);
    #endif
    #endif
}

#endif
