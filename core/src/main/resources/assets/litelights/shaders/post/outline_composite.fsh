#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:post_common.glsl>

// Lays the blurred silhouette over the screen as a glow in the outline colour, screen blended.
// First person: drawn before the hand, so the item itself covers the inside and only the contour
// shows. In the level (LITE_OUTLINE_CUT_INTERIOR): drawn over the level, so the items' own visible
// pixels are left out here instead, and each glows in its marker's colour (the glow colour, or a
// trimmed armour piece's trim colour), spread out in ColourSampler.

uniform sampler2D GlowSampler;
#ifdef LITE_OUTLINE_CUT_INTERIOR
uniform sampler2D ColourSampler;   // the markers' colours spread out, premultiplied by how much of them reached
uniform sampler2D MaskSampler;
uniform sampler2D SceneDepthSampler;
#endif

layout(push_constant) uniform OutlineComposite {
    vec4 Glow;     // rgb linear glow colour (in the level, where no marker colour reaches), a intensity
    vec4 Shape;    // x hardness (diffusion below 50 %), y softness (above 50 %), z the mask's clear depth (level)
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    #ifdef LITE_OUTLINE_CUT_INTERIOR
    float mask = texture(MaskSampler, texCoord).r;
    float scene = texture(SceneDepthSampler, texCoord).r;
    float nearMask = Shape.z < 0.5 ? mask : 1.0 - mask;
    float nearScene = Shape.z < 0.5 ? scene : 1.0 - scene;
    if (mask != Shape.z && nearMask >= nearScene * 0.98) {
        fragColor = vec4(0.0);
        return;
    }
    #endif
    float glow = texture(GlowSampler, texCoord).r;
    // The blurred silhouette is about one half at the edge and falls off outward. At 50 % diffusion
    // a square root widens the bright band so the contour reads clearly without a hard rim; softer,
    // it falls off linearly over a wider blur; crisper, it becomes a solid line out to where the
    // blur first reaches.
    float soft = clamp(glow * 2.0, 0.0, 1.0);
    float strength = mix(sqrt(soft), soft, Shape.y);
    strength = mix(strength, smoothstep(0.03, 0.06, glow), Shape.x);
    vec3 colour = Glow.rgb;
    #ifdef LITE_OUTLINE_CUT_INTERIOR
    vec4 marked = texture(ColourSampler, texCoord);
    if (marked.a > 0.001) {
        colour = marked.rgb / marked.a;
    }
    #endif
    fragColor = vec4(min(colour * strength * Glow.a, vec3(1.0)), 0.0);
}
