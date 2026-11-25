#ifndef LITELIGHTS_LIGHT_POST_RAYS_COMPOSITE_GLSL
#define LITELIGHTS_LIGHT_POST_RAYS_COMPOSITE_GLSL

#include <litelights:post_common.glsl>

// Reads the light rays texture (a quarter, half or the full screen's size) with a depth-aware tent
// filter and returns the sRGB colour to screen-blend.
//
// Each rays texel was worked out along the view line of the screen pixel at its centre, with that
// pixel's depth. The filter weighs the 4 x 4 texels around a pixel by a tent 1.5 texels wide, which
// also smooths the slices' dither, and by how close each texel's own depth is to the pixel's, so a
// leaf in front of bright sky takes the beams of the leaf, not of the sky: no stair-steps or halos
// along silhouettes at the lower resolutions. Where no texel matches (a thin twig) the plain tent
// takes over.
//
// The rays texture holds the scattered light (red), the shaded length (green) and the total length
// (blue) of each view line. With shadeGate on (in air), rays only show where a good part of the
// view line is shaded; air lit all the way would otherwise only add a flat haze.

float lite_rays_distance(vec2 uv, float depth) {
    return depth <= 0.0 ? 1.0e5 : length(lite_position_from_depth(uv, depth));
}

vec3 lite_rays_to_add(sampler2D rays, sampler2D depth, vec2 uv, vec2 texel, vec3 lightColor, bool shadeGate) {
    vec2 size = 1.0 / texel;
    ivec2 last = ivec2(size + 0.5) - 1;
    float here = lite_rays_distance(uv, texture(depth, uv).r);
    float tolerance = 0.05 * min(here, 4096.0) + 0.3;
    vec2 position = uv * size - 0.5;
    ivec2 base = ivec2(floor(position));
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    vec4 plain = vec4(0.0);
    float plainWeights = 0.0;
    for (int y = -1; y <= 2; y++) {
        for (int x = -1; x <= 2; x++) {
            ivec2 at = clamp(base + ivec2(x, y), ivec2(0), last);
            vec2 away = abs(vec2(base + ivec2(x, y)) - position);
            float tent = max(0.0, 1.5 - away.x) * max(0.0, 1.5 - away.y);
            if (tent <= 0.0) {
                continue;
            }
            vec2 centre = (vec2(at) + 0.5) * texel;
            float there = lite_rays_distance(centre, texture(depth, centre).r);
            float match = exp(-abs(there - here) / tolerance);
            vec4 value = texelFetch(rays, at, 0);
            sum += value * tent * match;
            weights += tent * match;
            plain += value * tent;
            plainWeights += tent;
        }
    }
    sum = weights > 0.02 * plainWeights ? sum / weights : plain / max(plainWeights, 1.0e-4);
    float shadedShare = sum.g / max(sum.b, 1.0e-3);
    float gate = shadeGate ? smoothstep(0.15, 0.55, shadedShare) : 1.0;
    return lite_linear_to_srgb(min(lightColor * (sum.r * gate), vec3(1.0)));
}

#endif
