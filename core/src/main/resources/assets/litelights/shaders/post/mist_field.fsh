#version 330
#extension GL_ARB_separate_shader_objects : require

// The Mist field (Mist style: Mist field, fog097), first pass, at half resolution: one low layer of
// drifting mist, dust or haze around the camera instead of puffs (particle.HazeField). Each pixel's
// line of sight is clipped to the slab the layer can lie in, to the scene behind and to the field's
// reach over the ground, then stepped through in a fixed twelve steps, finer near and coarser far:
// no loop runs on data. The layer lies on the land (fog098): at every step its bottom and top are
// heights over the ground there, from a small map of the land around the camera (FieldGround), so
// it fills valleys, climbs hills, gathers thicker and higher in hollows and rises in slow banks. The
// density is world-space noise drifting with the wind (include/particle/haze.glsl, the puffs' own),
// with the puffs' slow clumps and gaps. The field is one colour, so this pass only works out how
// much shows through, where along the line the mist is and how high in the layer, and how far the
// scene is here; mist_field_composite.fsh lights it and lays it over the full-resolution picture.

#include <litelights:post_common.glsl>
#include <litelights:skyfog/atmosphere.glsl>
#include <litelights:particle/haze.glsl>

uniform sampler2D FieldDepth;
// The land (FieldGround): r ground over the reference height, g how far the ground lies below the
// land around it, b whether there is ground (the End's void has none), a how far the top rises
// there (a bank of mist, or in among trees).
uniform sampler2D FieldGround;

layout(push_constant) uniform MistField {
    vec4 FieldColor;   // rgb colour before lighting, a extinction per block in the layer's core
    vec4 FieldLayer;   // x bottom and y top of the layer over its base; z share that is dust (streaked along the wind); w reach over the ground, in blocks
    vec4 FieldLight;   // xy block and sky light where the camera is (vanilla's light coordinates, 0 to 240); z how much of the layer lies on the land (its base the ground there, otherwise the camera); w the map's reference height, camera-relative
    vec4 FieldMap;     // xy the map's north-west corner, camera-relative; zw the lowest and highest the layer reaches anywhere, camera-relative
    vec4 FieldStack;   // x how many layers lie on one another (1 to 4), y how far up the layer's height each next one starts; zw unused
};

layout(location = 0) in vec2 texCoord;
// r what shows through (transmittance), g the mist's mean distance along the line, b its mean height
// in the layer (0 bottom, 1 top), a the scene's distance at this pixel (for the upsample).
layout(location = 0) out vec4 fieldOut;

// Twelve steps to the old reach and a little past it; eighteen for a longer one (Mist distance) or
// stacked layers, so the steps stay shorter than the mist's clumps.
const int STEPS = 12;
const int FAR_STEPS = 18;
const float FAR_STEPS_FROM = 128.0;
// FieldGround's span in blocks (128 cells of 4).
const float MAP_BLOCKS = 512.0;
// Stacked layers (Mist layers): each one up is this much thinner than the one below, and the mist
// thins by this share where two meet, so they read as layers and not as one tall bank.
const float STACK_THINNING = 0.72;
const float STACK_SEAM = 0.3;
// Soft edges: the layer fades in over this many blocks below its bottom, and over this share of its
// height above its top, so it has no flat floor or lid. HazeField's slab uses the same.
const float BOTTOM_SOFTNESS = 1.5;
const float TOP_SOFTNESS = 0.45;
// Pooling: in a hollow the top rises by this share of how far the ground lies below the land around
// it, so the mist's top flattens over it, and the mist there is up to this much thicker.
const float POOL_FILL = 0.7;
const float POOL_THICKENING = 0.6;
// Thins out within a few blocks of the eye, as the puffs do. Thick mist (a blizzard's) keeps the
// first stretch clearer still, so the snow close by shows in front of it (fog098).
const float NEAR_START = 2.0;
const float NEAR_END = 7.0;
const float STORM_NEAR_START = 4.0;
const float STORM_NEAR_END = 16.0;
// The finer noise octave is only worth its cost close by; past this it fades out.
const float FINE_REACH = 40.0;
// What the scene's distance reads as where there is only sky.
const float SKY_DISTANCE = 60000.0;

// The mist's density at a camera-relative point, as a share of its core's, and how high in the layer
// the point is (0 bottom, 1 top).
float lite_field_density(vec3 position, out float heightInLayer) {
    vec4 land = textureLod(FieldGround, (position.xz - FieldMap.xy) / MAP_BLOCKS, 0.0);
    float onLand = FieldLight.z;
    float base = onLand * (land.r + FieldLight.w);
    float pool = onLand * land.g;
    float bottom = base + FieldLayer.x;
    float top = base + FieldLayer.y + onLand * land.a + POOL_FILL * pool;
    float thickness = max(top - bottom, 0.5);
    // Further layers lie on the first, each starting a little under the top of the one below.
    float rise = thickness * FieldStack.y;
    float layers = max(FieldStack.x, 1.0);
    float stackTop = top + rise * (layers - 1.0);
    float softTop = max(TOP_SOFTNESS * thickness, 0.5);
    float profile = smoothstep(bottom - BOTTOM_SOFTNESS, bottom + BOTTOM_SOFTNESS, position.y)
        * (1.0 - smoothstep(stackTop - softTop, stackTop + softTop, position.y));
    if (layers > 1.5) {
        float storey = clamp((position.y - bottom) / rise, 0.0, layers - 0.001);
        // Thinnest where one layer hands over to the next.
        float seam = 1.0 - abs(2.0 * fract(storey) - 1.0);
        profile *= pow(STACK_THINNING, floor(storey)) * (1.0 - STACK_SEAM * step(0.5, storey) * (1.0 - smoothstep(0.0, 0.5, seam)));
    }
    heightInLayer = clamp((position.y - bottom) / max(stackTop - bottom, 0.5), 0.0, 1.0);
    float ground = mix(1.0, land.b, onLand);
    return profile * ground * (1.0 + POOL_THICKENING * smoothstep(0.0, 6.0, pool));
}

// How much of the mist at a point the haze and storm fog in front of it leave to be seen. Mist the
// fog already hides adds nothing the fog hasn't, and the composite can only colour the field at one
// distance, so far mist counted in full would show as a band of nearer-looking mist over the fogged
// distance (fog098, with the longer reach).
float lite_field_seen(vec3 position, float distance) {
    if (!lite_feature(LITE_FEATURE_LITE_FOG) || !lite_feature(LITE_FEATURE_HAZE)) {
        return 1.0;
    }
    float depth = lite_haze_depth(position, distance);
    if (lite_feature(LITE_FEATURE_STORM_FOG)) {
        depth += lite_storm_depth(distance);
    }
    return exp(-depth);
}

// The part of the line of sight inside the slab the layer lies in, as distances from the eye.
vec2 lite_field_span(vec3 eye, vec3 ray) {
    float low = FieldMap.z;
    float high = FieldMap.w;
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

    // The reach is over the ground, so looking down from high up still finds the mist below.
    float across = max(length(ray.xz), 1.0e-3);
    float stormy = smoothstep(0.12, 0.4, FieldColor.a);
    float nearStart = mix(NEAR_START, STORM_NEAR_START, stormy);
    float nearEnd = mix(NEAR_END, STORM_NEAR_END, stormy);
    vec2 span = lite_field_span(eye, ray);
    float start = max(span.x, nearStart);
    float end = min(min(span.y, sceneDistance), FieldLayer.w / across);
    if (end <= start) {
        return;
    }

    float spanLength = end - start;
    // A different start within each step per pixel, so the steps don't show as bands.
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 along = lite_haze_wind_axis();
    float clumps = lite_haze_clumps(lite_haze_world(eye + ray * min(0.5 * (start + end), start + 32.0)));

    float opticalDepth = 0.0;
    float weightedDistance = 0.0;
    float weightedHeight = 0.0;
    int steps = FieldLayer.w > FAR_STEPS_FROM || FieldStack.x > 1.5 ? FAR_STEPS : STEPS;
    for (int i = 0; i < FAR_STEPS; i++) {
        if (i >= steps) {
            break;
        }
        // Steps grow with the square of their number: about two blocks apart close by, sixteen far off.
        float share = (float(i) + jitter) / float(steps);
        float distance = start + spanLength * share * share;
        float stepLength = spanLength * float(2 * i + 1) / float(steps * steps);
        vec3 position = eye + ray * distance;
        float heightInLayer;
        float density = lite_field_density(position, heightInLayer);
        float fade = smoothstep(nearStart, nearEnd, distance) * (1.0 - smoothstep(FieldLayer.w * 0.55, FieldLayer.w, length(position.xz)));
        if (density * fade <= 0.0) {
            continue;
        }
        vec3 world = lite_haze_world(position);
        world -= along * (dot(world, along) * 0.5 * FieldLayer.z);
        float coarse = lite_haze_noise(world, 16.0);
        float fineWeight = 1.0 - smoothstep(FINE_REACH * 0.75, FINE_REACH, distance);
        float noise = coarse;
        if (fineWeight > 0.0) {
            noise = mix(coarse, 0.6 * coarse + 0.4 * lite_haze_noise(world, 8.0), fineWeight);
        }
        float shape = smoothstep(0.2, 0.8, noise) * 1.9;
        float stepDepth = FieldColor.a * density * shape * fade * lite_field_seen(position, distance) * stepLength;
        opticalDepth += stepDepth;
        weightedDistance += stepDepth * distance;
        weightedHeight += stepDepth * heightInLayer;
    }
    if (opticalDepth < 1.0e-5) {
        return;
    }
    fieldOut = vec4(exp(-opticalDepth * clumps), weightedDistance / opticalDepth, weightedHeight / opticalDepth, sceneDistance);
}
