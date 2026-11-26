#version 330
#extension GL_ARB_separate_shader_objects : require

// The volumetric Dynamic clouds, second pass, at full resolution (render.feature.clouds.CloudVolume):
// lays the march (volume_march.fsh) over the picture. Each marched pixel went as far as the
// farthest of the scene under it, so it holds the cloud behind a leaf or a block edge as well as
// beside it; here each screen pixel takes of the four marched pixels around it what lies in front
// of its own scene. Where its scene is about as far off as a marched pixel's, that pixel counts as
// it is. Where its scene is nearer (a leaf, a fence, the edge of a hill in front of the sky), the
// marched pixel's cloud counts only if that cloud stands in front of it; a cloud behind it counts as
// no cloud, so nothing of the cloud shows on or round what stands in front. Blended as picture times
// what shows through plus the cloud's light, keeping the target's alpha (bloom's mask).

#include <litelights:post_common.glsl>
#include <litelights:clouds/volume.glsl>

uniform sampler2D CloudMarch;
uniform sampler2D SceneDepth;

layout(push_constant) uniform CloudComposite {
    vec4 Scale;   // x screen pixels per marched pixel; yzw unused
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float SKY_DISTANCE = 60000.0;

float distanceAt(float depth, ivec2 pixel, ivec2 screen) {
    if (depth <= 0.0) {
        return SKY_DISTANCE;
    }
    vec2 uv = (vec2(pixel) + 0.5) / vec2(screen);
    return min(length(lite_position_from_depth(uv, depth) - lite_view_origin()), SKY_DISTANCE);
}

void main() {
    ivec2 screen = textureSize(SceneDepth, 0);
    ivec2 size = textureSize(CloudMarch, 0);
    int scale = int(Scale.x + 0.5);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float here = distanceAt(texelFetch(SceneDepth, pixel, 0).r, pixel, screen);
    // Within this much of each other, two distances count as the same surface.
    float sameWithin = 0.04 * min(here, 2048.0) + 0.5;
    vec2 marched = gl_FragCoord.xy / Scale.x - 0.5;
    ivec2 corner = ivec2(floor(marched));
    vec2 blend = marched - vec2(corner);
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    vec4 nearest = vec4(0.0, 0.0, 0.0, 1.0);
    float nearestGap = 1.0e9;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        ivec2 tapPixel = clamp(corner + offset, ivec2(0), size - 1);
        vec4 tap = texelFetch(CloudMarch, tapPixel, 0);
        vec4 cloud = vec4(tap.rgb, fract(tap.a));
        float front = floor(tap.a);
        // Where the marched pixel stopped: the farthest of the scene under it, read as the march read it.
        ivec2 middle = clamp(tapPixel * scale + scale / 2, ivec2(0), screen - 1);
        float there = distanceAt(lite_cloud_farthest_depth(SceneDepth, tapPixel, scale, screen), middle, screen);
        float bilinear = (offset.x == 1 ? blend.x : 1.0 - blend.x) * (offset.y == 1 ? blend.y : 1.0 - blend.y);
        float gap = there - here;
        float weight;
        if (abs(gap) <= sameWithin) {
            weight = bilinear;
        } else if (gap > 0.0) {
            // This pixel's scene is in front of where the march stopped: only a cloud in front of it shows.
            if (front <= 0.0 || front > here) {
                cloud = vec4(0.0, 0.0, 0.0, 1.0);
            }
            weight = bilinear;
        } else {
            weight = bilinear * exp(-(-gap) / sameWithin);
        }
        sum += cloud * weight;
        weights += weight;
        if (abs(gap) < nearestGap) {
            nearestGap = abs(gap);
            nearest = cloud;
        }
    }
    vec4 cloud = weights > 1.0e-3 ? sum / weights : nearest;
    if (cloud.a > 0.997) {
        discard;
    }
    fragColor = vec4(cloud.rgb, cloud.a);
}
