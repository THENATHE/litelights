#version 330
#extension GL_ARB_separate_shader_objects : require

// The Dynamic clouds' shadows on the land (render.feature.clouds.CloudShadows): one full-screen draw
// into the main target before translucent terrain, after the near and far land and before the
// clouds themselves, multiplying the land's colour by how much sunlight the clouds over it let
// through. Each pixel's point is followed up toward the sun to the clouds' layer and looked up in
// the shadow map (clouds.mesh.CloudShadowMap), which rides the wind with the clouds; the change
// between two builds blends from one map to the next. Alpha (bloom's mask) is left as it was.

#include <litelights:post_common.glsl>
#include <litelights:skyfog/atmosphere.glsl>

uniform sampler2D SceneDepth;
uniform sampler2D ShadowBefore;
uniform sampler2D ShadowAfter;

layout(push_constant) uniform CloudShadowValues {
    vec4 BeforePlace;   // xy the earlier map's corner, camera-relative x and z; z one over its width in blocks
    vec4 AfterPlace;    // the same for the later map
    vec4 ShadowShape;   // x the change's progress, y the clouds' layer (camera-relative y), z the deepest shade, w how far shadows reach (blocks)
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texelFetch(SceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    vec3 sun = LiteSunDirection.xyz;
    // Reversed depth: the sky is 0. No shadow with the sun at or under the horizon.
    if (depth <= 0.0 || sun.y < 0.03) {
        discard;
    }
    vec3 position = lite_position_from_depth(texCoord, depth);
    float toLayer = ShadowShape.y - position.y;
    if (toLayer <= 0.0) {
        discard;
    }
    vec2 under = position.xz + sun.xz * (toLayer / sun.y);
    float before = texture(ShadowBefore, (under - BeforePlace.xy) * BeforePlace.z).r;
    float after = texture(ShadowAfter, (under - AfterPlace.xy) * AfterPlace.z).r;
    float through = mix(before, after, ShadowShape.x);
    // Softer with a low sun (the light comes through more air and from more of the sky), gone by
    // night, fading at the reach's end, and lost in the haze as the land is.
    float strength = ShadowShape.z * smoothstep(0.03, 0.25, sun.y) * LiteSunColor.a;
    strength *= 1.0 - smoothstep(ShadowShape.w * 0.75, ShadowShape.w, length(under));
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
