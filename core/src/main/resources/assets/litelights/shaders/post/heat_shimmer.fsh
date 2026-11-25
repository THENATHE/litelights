#version 330
#extension GL_ARB_separate_shader_objects : require

// Heat shimmer (shimmer099, light-post's HeatShimmer): the picture wavers a little where the line of
// sight crosses hot air. Each pixel's line is clipped to the slab and box the heat lies in, to the
// scene behind and to 48 blocks, then stepped through in a fixed ten steps (finer near, coarser
// far; no loop runs on data), adding up the heat it passes: over a source it is strongest just
// above it and gone a few blocks up, and over open desert ground at noon it lies in a thin layer.
// Where there is enough, the picture is read a pixel or two aside, by ripples fixed in the world
// that rise slowly on the shared clock (still while paused). Elsewhere the pixel is left alone.

#include <litelights:post_common.glsl>
#include <litelights:noise.glsl>

uniform sampler2D ShimmerScene;
uniform sampler2D ShimmerDepth;
// The heat around the camera (HeatSources), one texel a block column, wrapping: r how hot its
// hottest exposed source is, g and b the lowest and highest heights its sources take (over the
// reference height), a 1 over open desert or badlands ground.
uniform sampler2D ShimmerHeat;

layout(push_constant) uniform HeatShimmer {
    vec4 HeatMap;     // xy where the camera stands on the map (texture coordinates, wrapping); z the reference height, camera-relative; w how hot open desert ground is now (0..1)
    vec4 HeatSlab;    // x, y the lowest and highest the hot air reaches, camera-relative; z reach in blocks; w the largest shift in pixels
    vec4 HeatBox;     // xy the hot cells' west and north edges, zw their east and south edges, camera-relative
    vec4 HeatUnused;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int STEPS = 10;
const float MAP_BLOCKS = 128.0;
// A source's heat rises this far above it (HeatShimmer.RISE), fading as it goes; open ground's
// stays in a thinner layer, and is gentler.
const float RISE = 4.0;
const float GROUND_RISE = 2.2;
const float GROUND_HEAT = 0.6;
// How much shimmer a block of hot air adds along the line of sight.
const float THICKNESS = 0.35;
// Lines of sight start this far out, past the near plane and the player's own hands' reach.
const float NEAR = 0.3;
// The ripples: blocks per ripple across and up, and how fast they rise, in blocks a second.
const float RIPPLE_ACROSS = 0.9;
const float RIPPLE_UP = 0.7;
const float RISE_SPEED = 1.3;
// What the scene's distance reads as where there is only sky.
const float SKY_DISTANCE = 60000.0;

// The heat at a camera-relative point, as a share of the hottest.
float lite_heat_at(vec3 position) {
    vec4 cell = textureLod(ShimmerHeat, HeatMap.xy + position.xz / MAP_BLOCKS, 0.0);
    float bottom = cell.g + HeatMap.z;
    float top = cell.b + HeatMap.z;
    float above = clamp((position.y - top) / RISE, 0.0, 1.0);
    float source = cell.r * smoothstep(bottom - 0.6, bottom, position.y) * (1.0 - above) * (1.0 - above);
    float aboveGround = clamp((position.y - top) / GROUND_RISE, 0.0, 1.0);
    float ground = cell.a * HeatMap.w * GROUND_HEAT * smoothstep(top - 0.5, top, position.y) * (1.0 - aboveGround) * (1.0 - aboveGround);
    return source + ground;
}

// The part of the line of sight inside an axis-aligned box, as distances from the eye.
vec2 lite_box_span(vec3 eye, vec3 ray, vec3 low, vec3 high) {
    // A line along an axis would divide by zero; nudged off it, it misses or crosses as it should.
    vec3 safe = vec3(abs(ray.x) < 1.0e-5 ? 1.0e-5 : ray.x, abs(ray.y) < 1.0e-5 ? 1.0e-5 : ray.y, abs(ray.z) < 1.0e-5 ? 1.0e-5 : ray.z);
    vec3 inverse = 1.0 / safe;
    vec3 toLow = (low - eye) * inverse;
    vec3 toHigh = (high - eye) * inverse;
    vec3 nearest = min(toLow, toHigh);
    vec3 farthest = max(toLow, toHigh);
    return vec2(max(max(nearest.x, nearest.y), nearest.z), min(min(farthest.x, farthest.y), farthest.z));
}

float lite_scene_distance(vec2 uv, vec3 eye) {
    float depth = texture(ShimmerDepth, uv).r;
    return depth > 0.0 ? length(lite_position_from_depth(uv, depth) - eye) : SKY_DISTANCE;
}

void main() {
    vec3 eye = lite_view_origin();
    vec3 ray = lite_view_direction(texCoord);
    float sceneDistance = lite_scene_distance(texCoord, eye);
    vec2 span = lite_box_span(eye, ray, vec3(HeatBox.x, HeatSlab.x, HeatBox.y), vec3(HeatBox.z, HeatSlab.y, HeatBox.w));
    float start = max(span.x, NEAR);
    float end = min(min(span.y, sceneDistance), HeatSlab.z);
    if (end <= start) {
        discard;
    }

    float spanLength = end - start;
    float heat = 0.0;
    float weightedDistance = 0.0;
    for (int i = 0; i < STEPS; i++) {
        // Steps grow with the square of their number, as the Mist field's do.
        float share = (float(i) + 0.5) / float(STEPS);
        float distance = start + spanLength * share * share;
        float stepLength = spanLength * float(2 * i + 1) / float(STEPS * STEPS);
        float fade = 1.0 - smoothstep(HeatSlab.z * 0.65, HeatSlab.z, distance);
        float here = lite_heat_at(eye + ray * distance) * fade * stepLength;
        heat += here;
        weightedDistance += here * distance;
    }
    float strength = 1.0 - exp(-THICKNESS * heat);
    if (strength < 0.003) {
        discard;
    }

    // Ripples fixed in the world where the hot air is, rising slowly; two of them, at an angle to
    // each other, for the sideways and the upward shift.
    float distance = weightedDistance / heat;
    vec3 world = lite_block_local(eye + ray * distance) + vec3(LiteCameraBlock.xyz & ivec3(1023));
    float rising = LiteTime.y * RISE_SPEED;
    float across = lite_value_noise(vec2((world.x + 0.6 * world.z) / RIPPLE_ACROSS, (world.y - rising) / RIPPLE_UP));
    float up = lite_value_noise(vec2((world.z - 0.6 * world.x) / RIPPLE_ACROSS + 7.3, (world.y - 0.8 * rising) / RIPPLE_UP + 3.1));
    vec2 shift = vec2(across - 0.5, 0.7 * (up - 0.5)) * 2.0 * strength * HeatSlab.w;
    vec2 shifted = texCoord + shift * LiteScreen.zw;

    // Never pull in something in front of the hot air (a post or a mob before the lava): only read
    // from pixels at least as far as the heat.
    if (lite_scene_distance(shifted, eye) < distance * 0.85) {
        discard;
    }
    fragColor = vec4(texture(ShimmerScene, shifted).rgb, 1.0);
}
