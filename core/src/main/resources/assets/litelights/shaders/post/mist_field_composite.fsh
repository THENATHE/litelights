#version 330
#extension GL_ARB_separate_shader_objects : require

// The Mist field (fog097), second pass, at full resolution: lays the half-resolution trace
// (mist_field.fsh) over the picture. Of the four half-resolution pixels around this one, those that
// saw about as far as this pixel does count most, so the mist keeps the edges of what stands in it
// instead of bleeding round them. Then it is lit as the puffs are, through the lightmap at the
// camera's light and a shade darker low down, and covered by LiteLights' air in front of it. Blended
// as picture * T + colour * (1 - T), keeping the target's alpha (bloom's mask).

#include <litelights:post_common.glsl>
#include <litelights:skyfog/atmosphere.glsl>

uniform sampler2D FieldMarch;
uniform sampler2D FieldDepth;
uniform sampler2D FieldLightmap;

layout(push_constant) uniform MistField {
    vec4 FieldColor;   // rgb colour before lighting, a extinction per block in the layer's core
    vec4 FieldLayer;   // x bottom and y top of the layer over its base; z share that is dust; w reach over the ground, in blocks
    vec4 FieldLight;   // xy block and sky light where the camera is (vanilla's light coordinates, 0 to 240); zw the land map's (mist_field.fsh)
    vec4 FieldMap;     // the land map's corner and the layer's slab (mist_field.fsh)
    vec4 FieldStack;   // the stacked layers (mist_field.fsh)
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float SKY_DISTANCE = 60000.0;

void main() {
    vec3 eye = lite_view_origin();
    float depth = texelFetch(FieldDepth, ivec2(gl_FragCoord.xy), 0).r;
    float sceneDistance = depth > 0.0 ? min(length(lite_position_from_depth(texCoord, depth) - eye), SKY_DISTANCE) : SKY_DISTANCE;

    ivec2 size = textureSize(FieldMarch, 0);
    vec2 halfPosition = gl_FragCoord.xy * 0.5 - 0.5;
    ivec2 corner = ivec2(floor(halfPosition));
    vec2 blend = halfPosition - vec2(corner);
    // Four fixed taps: bilinear weights times how alike the distances are.
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    vec4 nearest = vec4(1.0, 0.0, 0.0, 0.0);
    float nearestGap = 1.0e9;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        vec4 tap = texelFetch(FieldMarch, clamp(corner + offset, ivec2(0), size - 1), 0);
        float bilinear = (offset.x == 1 ? blend.x : 1.0 - blend.x) * (offset.y == 1 ? blend.y : 1.0 - blend.y);
        float gap = abs(tap.a - sceneDistance);
        float alike = exp(-gap / (0.04 * min(sceneDistance, 512.0) + 0.3));
        sum += tap * bilinear * alike;
        weights += bilinear * alike;
        if (gap < nearestGap) {
            nearestGap = gap;
            nearest = tap;
        }
    }
    vec4 field = weights > 1.0e-3 ? sum / weights : nearest;
    float transmittance = field.r;
    if (transmittance > 0.999) {
        discard;
    }

    vec3 light = texture(FieldLightmap, clamp(FieldLight.xy / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
    float shade = mix(0.86, 1.04, field.b);
    vec3 color = FieldColor.rgb * light * shade;
    if (lite_feature(LITE_FEATURE_LITE_FOG) && lite_feature(LITE_FEATURE_HAZE)) {
        vec4 air = lite_air(lite_view_direction(texCoord) * field.g, clamp(FieldLight.y / 240.0, 0.0, 1.0));
        color = mix(color, air.rgb, air.a);
    }
    fragColor = vec4(color * (1.0 - transmittance), transmittance);
}
