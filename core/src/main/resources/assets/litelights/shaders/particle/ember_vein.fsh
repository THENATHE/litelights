#version 330
#extension GL_ARB_separate_shader_objects : require

// Ember veins add their glow over the block face and mark it for bloom in the main target's alpha,
// as glowing blocks do (emissive.glsl). The blend adds the colour and keeps the lower alpha, so a
// vein never takes the mark away from something that glows more.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <litelights:frame.glsl>
#include <litelights:emissive.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;
layout(location = 4) in float liteAirCover;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    if (texel.a < 0.5) {
        discard;
    }
    float brightness = vertexColor.a;
    vec3 glow = texel.rgb * vertexColor.rgb * brightness;
    if (lite_feature(LITE_FEATURE_LITE_FOG)) {
        glow *= 1.0 - liteAirCover;
    } else {
        // Vanilla's fog fades the glow toward nothing, which is the same as fading it into the fog
        // colour of the face under it.
        glow = apply_fog(vec4(glow, 1.0), sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, vec4(0.0, 0.0, 0.0, 1.0)).rgb;
    }
    float alpha = 1.0;
    if (lite_world_pass() && lite_feature(LITE_FEATURE_BLOOM_MASK)) {
        float strength = max(texel.r, max(texel.g, texel.b)) * brightness;
        if (strength > 0.05) {
            alpha = lite_emissive_to_alpha(clamp(strength, 0.0, 1.0));
        }
    }
    fragColor = vec4(glow, alpha);
}
