#version 330
#extension GL_ARB_separate_shader_objects : require

// Soft particles keep their faint edges: vanilla's particle shader drops anything under 10 %
// opacity, which cut mist puffs into hard-edged squares. Only fully clear texels are dropped here.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <litelights:frame.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;
layout(location = 4) in vec4 liteAir;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.004) {
        discard;
    }
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    if (lite_feature(LITE_FEATURE_LITE_FOG)) {
        // LiteLights' fog replaces vanilla's here, as it does on the terrain behind.
        color.rgb = mix(color.rgb, liteAir.rgb, liteAir.a);
    }
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    if (lite_feature(LITE_FEATURE_LITE_FOG)) {
        fragColor = color;
    } else {
        fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
    }
    #endif
}
