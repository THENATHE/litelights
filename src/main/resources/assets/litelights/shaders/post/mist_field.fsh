#version 330
#extension GL_ARB_separate_shader_objects : require

// The Mist field (Mist style: Mist field, fog097), first pass, at half resolution: one low layer of
// drifting mist, dust or haze around the camera instead of puffs (particle.HazeField). Each pixel's
// line of sight is clipped to the layer (with soft edges above and below), to the scene behind and
// to the field's reach, then stepped through in a fixed eight steps: no loop runs on data. The
// density is world-space noise drifting with the wind (include/particle/haze.glsl, the puffs' own),
// with the puffs' slow clumps and gaps. The field is one colour, so this pass only works out how
// much shows through, where along the line the mist is and how high in the layer, and how far the
// scene is here; mist_field_composite.fsh lights it and lays it over the full-resolution picture.

#include <litelights:post_common.glsl>
#include <litelights:skyfog/atmosphere.glsl>
#include <litelights:particle/haze.glsl>

uniform sampler2D FieldDepth;

layout(push_constant) uniform MistField {
    vec4 FieldColor;   // rgb colour before lighting, a extinction per block in the layer's core
    vec4 FieldLayer;   // x bottom and y top of the layer, camera-relative heights; z share that is dust (streaked along the wind); w reach in blocks
    vec4 FieldLight;   // xy block and sky light where the camera is (vanilla's light coordinates, 0 to 240); z, w unused
    vec4 FieldSpare;   // unused
};

layout(location = 0) in vec2 texCoord;
// r what shows through (transmittance), g the mist's mean distance along the line, b its mean height
// in the layer (0 bottom, 1 top), a the scene's distance at this pixel (for the upsample).
layout(location = 0) out vec4 fieldOut;

const int STEPS = 8;
// Soft edges: the layer fades in over this many blocks below its bottom, and over this share of its
// height above its top, so it has no flat floor or lid.
const float BOTTOM_SOFTNESS = 1.5;
const float TOP_SOFTNESS = 0.45;
// Thins out within a few blocks of the eye, as the puffs do.
const float NEAR_START = 2.0;
const float NEAR_END = 7.0;
// What the scene's distance reads as where there is only sky.
const float SKY_DISTANCE = 60000.0;

float lite_field_profile(float height) {
    float softTop = max(TOP_SOFTNESS * (FieldLayer.y - FieldLayer.x), 0.5);
    return smoothstep(FieldLayer.x - BOTTOM_SOFTNESS, FieldLayer.x + BOTTOM_SOFTNESS, height)
        * (1.0 - smoothstep(FieldLayer.y - softTop, FieldLayer.y + softTop, height));
}

// The part of the line of sight inside the layer's soft bounds, as distances from the eye.
vec2 lite_field_span(vec3 eye, vec3 ray) {
    float softTop = max(TOP_SOFTNESS * (FieldLayer.y - FieldLayer.x), 0.5);
    float low = FieldLayer.x - BOTTOM_SOFTNESS;
    float high = FieldLayer.y + softTop;
    if (abs(ray.y) < 1.0e-4) {
        return eye.y >= low && eye.y <= high ? vec2(0.0, 1.0e6) : vec2(1.0, 0.0);
    }
    float toLow = (low - eye.y) / ray.y;
    float toHigh = (high - eye.y) / ray.y;
    return vec2(max(min(toLow, toHigh), 0.0), max(toLow, toHigh));
}

void main() {
    vec3 eye = lite_view_origin();
    vec3 ray = lite_view_direction(texCoord);
    // One of the four full-resolution pixels under this one: the upsample weighs by this distance.
    float depth = texelFetch(FieldDepth, ivec2(gl_FragCoord.xy) * 2, 0).r;
    float sceneDistance = depth > 0.0 ? min(length(lite_position_from_depth(texCoord, depth) - eye), SKY_DISTANCE) : SKY_DISTANCE;
    fieldOut = vec4(1.0, 0.0, 0.0, sceneDistance);

    vec2 span = lite_field_span(eye, ray);
    float start = max(span.x, NEAR_START);
    float end = min(min(span.y, sceneDistance), FieldLayer.w);
    if (end <= start) {
        return;
    }

    float stepLength = (end - start) / float(STEPS);
    // A different start within the first step per pixel, so the steps don't show as bands.
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 along = lite_haze_wind_axis();
    float clumps = lite_haze_clumps(lite_haze_world(eye + ray * (0.5 * (start + end))));

    float opticalDepth = 0.0;
    float weightedDistance = 0.0;
    float weightedHeight = 0.0;
    for (int i = 0; i < STEPS; i++) {
        float distance = start + (float(i) + jitter) * stepLength;
        vec3 position = eye + ray * distance;
        vec3 world = lite_haze_world(position);
        world -= along * (dot(world, along) * 0.5 * FieldLayer.z);
        float noise = 0.6 * lite_haze_noise(world, 16.0) + 0.4 * lite_haze_noise(world, 8.0);
        float shape = smoothstep(0.2, 0.8, noise) * 1.9;
        float fade = smoothstep(NEAR_START, NEAR_END, distance) * (1.0 - smoothstep(FieldLayer.w * 0.6, FieldLayer.w, distance));
        float stepDepth = FieldColor.a * lite_field_profile(position.y) * shape * fade * stepLength;
        opticalDepth += stepDepth;
        weightedDistance += stepDepth * distance;
        weightedHeight += stepDepth * clamp((position.y - FieldLayer.x) / max(FieldLayer.y - FieldLayer.x, 0.5), 0.0, 1.0);
    }
    if (opticalDepth < 1.0e-5) {
        return;
    }
    fieldOut = vec4(exp(-opticalDepth * clumps), weightedDistance / opticalDepth, weightedHeight / opticalDepth, sceneDistance);
}
