#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:frame.glsl>
#include <litelights:shadow.glsl>
#include <litelights:post_common.glsl>
#include <litelights:light_post/ray_pattern.glsl>

// Sunbeams as a stack of camera-facing billboards (one instance per slice, at growing
// distances), drawn additively at quarter resolution. A slice adds the sunlight scattered toward the
// camera by the air between it and the previous slice, where the sun shadow map says sunlight
// reaches; leaves, walls and cave mouths cut that light into beams. Slices behind the level's
// surface draw nothing. A small per-pixel dither of each slice's sample point hides the steps
// between slices.
//
// The target holds three sums along the pixel's view line: red the scattered light (the composite
// gives it the sun's colour), green the length of shaded air, blue the length of air looked at.
// Air that is lit all the way (open ground, over a forest, the sky) only makes a flat haze, not
// beams, so in air the composite shows rays only where a good part of the view line is shaded:
// under trees, through windows, at cave mouths.

uniform sampler2D DepthSampler;

layout(push_constant) uniform LightRays {
    vec4 Slices;   // x slice count, y first slice distance, z distance ratio between slices, w farthest distance
    vec4 Light;    // x strength of the scattered light, w phase asymmetry g
    vec4 Medium;   // x density per block, y absorption per block (under water), z underwater (0 or 1), w isotropic share
};

layout(location = 0) in vec2 texCoord;
layout(location = 1) flat in int slice;
layout(location = 0) out vec4 fragColor;

float phase(float cosine, float g) {
    float g2 = g * g;
    float denominator = 1.0 + g2 - 2.0 * g * cosine;
    return (1.0 - g2) / (12.566 * denominator * sqrt(denominator));
}

float bayer4(ivec2 pixel) {
    int index = (pixel.x & 3) + (pixel.y & 3) * 4;
    const float pattern[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
    return (pattern[index] + 0.5) / 16.0;
}

void main() {
    float near = Slices.y * pow(Slices.z, float(slice));
    float far = min(Slices.y * pow(Slices.z, float(slice + 1)), Slices.w);
    float depth = texture(DepthSampler, texCoord).r;
    // Distances are along the line of sight from the eye, which view bobbing moves off the camera.
    vec3 eye = lite_view_origin();
    vec3 direction = lite_view_direction(texCoord);
    float surface = depth <= 0.0 ? 1.0e9 : length(lite_position_from_depth(texCoord, depth) - eye);
    if (near >= surface || near >= far) {
        discard;
    }
    float end = min(far, surface);
    float span = end - near;
    float sampleDistance = near + span * bayer4(ivec2(gl_FragCoord.xy));
    vec3 position = eye + direction * sampleDistance;

    float lit = lite_shadow_lookup(LiteSunShadow, LiteSunShadowMatrix, position, LiteShadowMapInfo.x, 0.02);
    // The beams fade out gently over the far 60 % of the slices' reach (fog098), so walking toward a
    // beam it grows nearer instead of a wall of light sliding along ahead. Past the shadow map's reach
    // every point would count as lit; the farthest slice stops short of it, and this makes sure.
    float reach = (1.0 - smoothstep(Slices.w * 0.4, Slices.w, sampleDistance))
        * (1.0 - smoothstep(LiteShadowParams.w * 0.9, LiteShadowParams.w, length(position.xz)));
    float shadedLength = (1.0 - lit) * reach * span;
    lit *= reach;

    bool underwater = Medium.z > 0.5;
    vec3 travel = lite_lp_ray_direction(underwater);
    float scatter = mix(phase(dot(direction, -travel), Light.w), 0.08, Medium.w);
    float pattern = lite_lp_ray_pattern(position, underwater);
    float transmittance = exp(-Medium.y * sampleDistance);
    fragColor = vec4(Light.x * lit * scatter * pattern * transmittance * Medium.x * span, shadedLength, reach * span, 0.0);
}
