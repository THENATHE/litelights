#version 330
#extension GL_ARB_separate_shader_objects : require

// The Dynamic clouds' shadows on the land (render.feature.clouds.CloudShadows): one full-screen draw
// into the main target before translucent terrain, after the near and far land and before the
// clouds themselves, multiplying the land's colour by how much sunlight the clouds over it let
// through. From the same column maps the clouds are drawn from (clouds.volume.CloudMap, blended
// between builds as the clouds are), so a shadow lies where its cloud is: each pixel's point is
// followed up toward the sun to the middle of each band of sky, and the cloud there takes away
// light by how much of it the sun crosses. Alpha (bloom's mask) is left as it was.

#include <litelights:post_common.glsl>
#include <litelights:skyfog/atmosphere.glsl>
#include <litelights:clouds/volume.glsl>

uniform sampler2D SceneDepth;
uniform sampler2D NearMap;
uniform sampler2D FarMap;

layout(push_constant) uniform CloudShadowValues {
    vec4 NearPlace;     // xy the near map's corner, camera-relative; z its blocks per texel; w how far from its middle it is used
    vec4 FarPlace;      // the same for the far map; w unused
    vec4 NearCentre;    // xy the near map's middle, camera-relative; z unused; w how far shadows reach (blocks)
    vec4 ShadowShape;   // x the floor (camera-relative y), y the deepest shade, z light through a block of solid cloud (extinction); w unused
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// Where the sun's line crosses each band (blocks over the floor), and how much of a span's height
// the noise leaves solid on average (volume_march.fsh wears its edges away).
const float BAND_MIDDLE[4] = float[](160.0, 460.0, 860.0, 1300.0);
const float BAND_THICKNESS[4] = float[](1.0, 0.85, 0.7, 0.3);
const float SOLID_SHARE = 0.45;

vec4 band(vec2 xz, int index) {
    if (length(xz - NearCentre.xy) <= NearPlace.w) {
        return lite_cloud_band(NearMap, NearPlace.xyz, xz, index);
    }
    return lite_cloud_band(FarMap, FarPlace.xyz, xz, index);
}

void main() {
    float depth = texelFetch(SceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    vec3 sun = LiteSunDirection.xyz;
    // Reversed depth: the sky is 0. No shadow with the sun at or under the horizon.
    if (depth <= 0.0 || sun.y < 0.03) {
        discard;
    }
    vec3 position = lite_position_from_depth(texCoord, depth);
    float depthThrough = 0.0;
    vec2 lastUnder = position.xz;
    for (int i = 0; i < 4; i++) {
        float toBand = ShadowShape.x + BAND_MIDDLE[i] - position.y;
        if (toBand <= 0.0) {
            continue;
        }
        vec2 under = position.xz + sun.xz * (toBand / sun.y);
        lastUnder = under;
        vec4 span = band(under, i);
        float thickness = (span.g - span.r) * LITE_CLOUD_HEIGHT_RANGE;
        depthThrough += span.b * BAND_THICKNESS[i] * thickness * SOLID_SHARE / sun.y;
    }
    float through = exp(-depthThrough * ShadowShape.z);
    // Softer with a low sun (the light comes through more air and from more of the sky), gone by
    // night, fading at the reach's end, and lost in the haze as the land is.
    float strength = ShadowShape.y * smoothstep(0.03, 0.25, sun.y) * LiteSunColor.a;
    strength *= 1.0 - smoothstep(NearCentre.w * 0.75, NearCentre.w, length(lastUnder));
    float distance = length(position);
    // Nor on land already faded out at the render distance's edge (out where the far land ends
    // while far terrain draws): there the shadow would show as a grey cloud floating in the haze.
    strength *= 1.0 - lite_edge_fog(position);
    if (lite_feature(LITE_FEATURE_HAZE)) {
        strength *= exp(-lite_haze_depth(position, distance));
    }
    // The storm fog (and a cloud's white-out round the camera) covers the land as the haze does.
    if (lite_feature(LITE_FEATURE_STORM_FOG)) {
        strength *= exp(-lite_storm_depth(distance));
    }
    float shade = 1.0 - strength * (1.0 - through);
    if (shade >= 0.999) {
        discard;
    }
    fragColor = vec4(vec3(shade), 1.0);
}
