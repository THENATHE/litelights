#version 330
#extension GL_ARB_separate_shader_objects : require

// The volumetric Dynamic clouds, between the march and the composite, at the march's resolution
// (render.feature.clouds.CloudVolume): each frame's march starts its steps at another point within
// the first step, and this pass blends it with the frames before, so the steps' pattern averages
// out into smooth cloud instead of showing as speckle. Last frame's result is read where this
// pixel's cloud was then (projected through last frame's camera), and held within the range of
// this frame's pixels around it, so a cloud that moved, or land that passed in front, leaves no
// ghost.

#include <litelights:post_common.glsl>

uniform sampler2D CloudMarch;
uniform sampler2D CloudHistory;

layout(push_constant) uniform CloudResolve {
    vec4 Resolve;   // x how much of this frame goes in (1: no history), yzw unused
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 cloudOut;

// Where a pixel with no cloud in front of anything is taken to be, for its reprojection: far off,
// where moving the camera changes nothing.
const float FAR_AWAY = 4000.0;
// How much wider than the neighbours' range the history may stay.
const float CLAMP_MARGIN = 0.25;

vec4 decode(vec4 marched) {
    return vec4(marched.rgb, fract(marched.a));
}

// Last frame's result at a point between its pixels, each pixel decoded before they are blended.
// The alpha packs a whole distance with the share shown through, so letting the sampler blend it
// mixed a pixel with no cloud in front (0) and one with a cloud 600 blocks off into nonsense: the
// share came out anywhere from 0 to 1, and while the camera moved (so last frame's pixels never
// lined up with this frame's) a cloud's soft edge broke into the march's raw speckle and shimmered.
vec4 history(vec2 uv) {
    ivec2 size = textureSize(CloudHistory, 0);
    vec2 texel = uv * vec2(size) - 0.5;
    ivec2 corner = ivec2(floor(texel));
    vec2 blend = texel - vec2(corner);
    vec4 a = decode(texelFetch(CloudHistory, clamp(corner, ivec2(0), size - 1), 0));
    vec4 b = decode(texelFetch(CloudHistory, clamp(corner + ivec2(1, 0), ivec2(0), size - 1), 0));
    vec4 c = decode(texelFetch(CloudHistory, clamp(corner + ivec2(0, 1), ivec2(0), size - 1), 0));
    vec4 d = decode(texelFetch(CloudHistory, clamp(corner + ivec2(1, 1), ivec2(0), size - 1), 0));
    return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

void main() {
    ivec2 size = textureSize(CloudMarch, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 marched = texelFetch(CloudMarch, pixel, 0);
    vec4 current = decode(marched);
    float front = floor(marched.a);
    if (Resolve.x >= 0.999) {
        cloudOut = marched;
        return;
    }
    vec4 lowest = current;
    vec4 highest = current;
    for (int i = 0; i < 9; i++) {
        if (i == 4) {
            continue;
        }
        ivec2 offset = ivec2(i % 3 - 1, i / 3 - 1);
        vec4 around = decode(texelFetch(CloudMarch, clamp(pixel + offset, ivec2(0), size - 1), 0));
        lowest = min(lowest, around);
        highest = max(highest, around);
    }
    vec3 position = lite_view_origin() + lite_view_direction(texCoord) * (front > 0.0 ? front : FAR_AWAY);
    vec4 clip = LiteViewProjectionPrev * vec4(position, 1.0);
    vec2 previous = clip.xy / clip.w * 0.5 + 0.5;
    if (clip.w <= 0.0 || any(lessThan(previous, vec2(0.0))) || any(greaterThan(previous, vec2(1.0)))) {
        cloudOut = marched;
        return;
    }
    // The range of this frame's pixels round this one, a little wider: at a soft edge the march's
    // staggered starts make neighbours differ by more than the edge itself, and a tight range
    // pulled the settled history back toward this frame's speckle while the camera moved.
    vec4 margin = (highest - lowest) * CLAMP_MARGIN;
    vec4 before = clamp(history(previous), lowest - margin, highest + margin);
    vec4 blended = mix(before, current, Resolve.x);
    cloudOut = vec4(blended.rgb, front + min(blended.a, 0.998));
}
