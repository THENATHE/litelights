#version 330
#extension GL_ARB_separate_shader_objects : require

// Soft particles keep their faint edges: vanilla's particle shader drops anything under 10 %
// opacity, which cut mist puffs into hard-edged squares. Only fully clear texels are dropped here.
//
// LITE_HAZE: the haze kinds' classic billboards (Mist style: Classic billboards, fog097). They fade
// out where they come close to the opaque scene behind, so a sprite cutting into the ground or a
// wall has no hard line, and the drifting world-space noise the 3D puffs use breaks them up, so the
// pattern moves with the wind instead of with the sprite.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <litelights:frame.glsl>
#ifdef LITE_HAZE
#include <litelights:particle/haze.glsl>
#endif

uniform sampler2D Sampler0;
#ifdef LITE_HAZE
uniform sampler2D LiteHazeDepth;

// Over how many blocks in front of the scene a billboard fades out.
const float HAZE_SOFT_DISTANCE = 2.0;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;
layout(location = 4) in vec4 liteAir;
#ifdef LITE_HAZE
layout(location = 5) in vec3 spritePoint;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    #ifdef LITE_HAZE
    // Clear texels are dropped before paying for the scene distance and the noise.
    if (color.a < 0.004) {
        discard;
    }
    if (textureSize(LiteHazeDepth, 0).x > 1) {
        float sceneDistance = texelFetch(LiteHazeDepth, ivec2(gl_FragCoord.xy), 0).r;
        color.a *= smoothstep(0.0, HAZE_SOFT_DISTANCE, sceneDistance - length(spritePoint - lite_view_origin()));
    }
    color.a *= mix(0.45, 1.55, lite_haze_noise(lite_haze_world(spritePoint), 4.0));
    #endif
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
