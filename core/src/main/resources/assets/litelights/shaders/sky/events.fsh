#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:frame.glsl>
#if defined(RAINBOW) || (defined(WORLD) && !defined(DEPTH_ONLY))
#include <litelights:post_common.glsl>

// The scene's depth before translucent terrain: the solid world, far terrain and the Dynamic
// clouds' fronts (where they hide more than half of what is behind).
uniform sampler2D SceneDepth;

// How far from the eye the scene at this pixel is, in blocks; the sky is very far.
float lite_scene_distance() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    if (depth <= 0.0) {
        return 1.0e9;
    }
    vec2 uv = (vec2(pixel) + 0.5) / vec2(textureSize(SceneDepth, 0));
    return length(lite_position_from_depth(uv, depth) - lite_view_origin());
}
#endif

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

// Light comes in steps rather than a smooth ramp, so the sky events read as pixel art next to
// vanilla's square stars.
float lite_steps(float value, float count) {
    return floor(clamp(value, 0.0, 1.0) * count + 0.5) / count;
}

float lite_sin_hash(float n) {
    return fract(sin(n * 12.9898) * 43758.5453);
}

#ifdef AURORA
// The curtain adds light and never covers anything: its colour is already multiplied by its
// brightness and the pipeline adds it (one, one), so stars shine through.
//
// texCoord0.x runs along the curtain in ray widths, texCoord0.y from the lower edge (0, about
// 100 km up) to the top (1, about 300 km). vertexColor: r how strong tonight's magenta lower fringe
// is, g how strong the red tops are, b how edge-on the sheet is seen here (brighter along folds),
// a the curtain's strength.
//
// From the bottom up, as in photographs of strong displays: a thin magenta fringe (nitrogen,
// below about 100 km), a touch of yellow-green where it meets the green, the bright green body
// (oxygen's 557.7 nm line) fading upward, and faint red-violet tops (oxygen's 630 nm line, high
// up). Thin vertical rays, each reaching its own height and flickering slowly, are gathered into
// broader bundles; a slow ripple of brightness travels along. Height snaps to fine rows and
// brightness to small steps, so it keeps a pixel feel without coarse blocks.
const vec3 LITE_AURORA_MAGENTA = vec3(0.95, 0.24, 0.78);
const vec3 LITE_AURORA_YELLOW_GREEN = vec3(0.62, 1.0, 0.24);
const vec3 LITE_AURORA_GREEN = vec3(0.2, 1.0, 0.45);
const vec3 LITE_AURORA_TEAL = vec3(0.16, 0.82, 0.62);
const vec3 LITE_AURORA_RED = vec3(0.82, 0.16, 0.42);

// One ray's own brightness (x) and how high it reaches before it fades (y).
vec2 lite_aurora_ray(float ray, float along, float time) {
    float flicker = 0.65 + 0.35 * sin(time * (0.3 + lite_sin_hash(ray) * 0.5) + lite_sin_hash(ray + 7.0) * 6.2831);
    float bundle = 0.55 + 0.45 * sin(along * 0.11 + lite_sin_hash(floor(along / 9.0)) * 6.2831 + time * 0.06);
    // A soft glow under the rays, as in photographs: not all the light is in the rays.
    float strength = 0.3 + 0.7 * mix(0.45, 1.0, lite_sin_hash(ray + 3.0)) * flicker * bundle;
    return vec2(strength, mix(0.45, 1.0, lite_sin_hash(ray + 11.0)));
}

void main() {
    float time = LiteTime.y;
    float along = texCoord0.x;
    // Rows and rays stay a few pixels wide however near or far a band is: a band overhead is cut
    // into more of them, a band on the horizon into fewer. Powers of two, so they don't swim.
    float rows = exp2(clamp(floor(log2(0.25 / max(fwidth(texCoord0.y), 1.0e-5))), 4.0, 10.0));
    float height = floor(clamp(texCoord0.y, 0.0, 1.0) * rows + 0.5) / rows;
    float group = exp2(clamp(ceil(log2(max(fwidth(along), 1.0e-5) * 3.0)), -3.0, 6.0));

    // Each ray blends into the next over the middle of its width, so the rays read as soft
    // streaks rather than hard bars.
    float position = along / group - 0.5;
    float ray = floor(position) * group;
    vec2 rayValues = mix(lite_aurora_ray(ray, along, time), lite_aurora_ray(ray + group, along, time),
        smoothstep(0.2, 0.8, fract(position)));
    float rayStrength = rayValues.x;
    float reach = rayValues.y;
    float ripple = 0.75 + 0.25 * sin(along * 0.045 - time * 0.3);

    // The lower edge is soft: the fringe and the green body fade in over a band of rows rather
    // than starting at a line.
    float fringe = vertexColor.r * exp(-pow((height - 0.03) / 0.036, 2.0));
    float body = smoothstep(0.012, 0.085, height) * exp(-max(height - 0.075, 0.0) / (0.2 * reach));
    float tops = vertexColor.g * smoothstep(0.25, 0.5, height) * (1.0 - smoothstep(0.55 * reach + 0.3, 0.98, height));

    vec3 green = mix(LITE_AURORA_YELLOW_GREEN, LITE_AURORA_GREEN, smoothstep(0.05, 0.2, height));
    green = mix(green, LITE_AURORA_TEAL, smoothstep(0.3, 0.6, height));
    vec3 color = LITE_AURORA_MAGENTA * fringe + green * body + LITE_AURORA_RED * tops * 0.3;

    float edgeOn = 1.0 + vertexColor.b * 2.0;
    color *= rayStrength * ripple * edgeOn;
    // One step count for the whole colour, so the hues stay as they are while the light is stepped.
    float level = max(color.r, max(color.g, color.b));
    color *= lite_steps(level, 16.0) / max(level, 1.0e-4);
    fragColor = vec4(color * vertexColor.a * 0.95, 1.0);
}
#elif defined(RAINBOW)
// A rainbow after the rain (idea by AppleNButter, You Must Have The Rain). Its light is added after
// the clouds, before translucent terrain, over the scene by its distance: a rainbow is the light of
// raindrops all along the line of sight, so it shows over the sky, far cloud and far land and fades
// out over what is near (lite_rainbow_over); water, glass and particles are drawn over it later.
// texCoord0 holds the point opposite the sun (its azimuth from north turning east, and its
// elevation, radians) and vertexColor.a the rainbow's strength; each pixel's angle from that point
// says what it shows.
//
// The main bow lies 40.5 to 42.5 degrees out, red outside, violet inside; the second bow, fainter
// and wider, 50 to 53.5 degrees out with its colours the other way round. The sky inside the main
// bow is a little brighter and the band between the bows gets nothing, so it reads darker
// (Alexander's dark band). The colours come in six solid bands, so it reads as pixel art like the
// other sky events, and the bow fades out toward the horizon.
layout(location = 2) in vec3 skyDirection;

const vec3 LITE_RAINBOW_BANDS[6] = vec3[6](vec3(1.0, 0.22, 0.2), vec3(1.0, 0.58, 0.16), vec3(1.0, 0.92, 0.3),
    vec3(0.35, 0.92, 0.32), vec3(0.3, 0.55, 1.0), vec3(0.58, 0.32, 0.95));
// How bright each band shows: the outer red and the violet fade into the sky.
const float LITE_RAINBOW_WEIGHTS[6] = float[6](0.65, 0.95, 1.0, 0.95, 0.8, 0.5);
// Over nothing nearer than this the bow shows, and over all from this far, in blocks
// (RainbowSchedule.shownOver, NEAR_NONE and NEAR_FULL).
const float LITE_RAINBOW_NEAR_NONE = 300.0;
const float LITE_RAINBOW_NEAR_FULL = 1500.0;

// How much of the bow shows over what lies this far away.
float lite_rainbow_over(float distance) {
    return smoothstep(LITE_RAINBOW_NEAR_NONE, LITE_RAINBOW_NEAR_FULL, distance);
}

// The colour of a bow at a place across it, 0 at its red edge to 1 at its violet edge.
vec3 lite_rainbow_band(float across) {
    if (across < 0.0 || across >= 1.0) {
        return vec3(0.0);
    }
    int band = int(across * 6.0);
    return LITE_RAINBOW_BANDS[band] * LITE_RAINBOW_WEIGHTS[band];
}

void main() {
    vec3 direction = normalize(skyDirection);
    float horizontal = cos(texCoord0.y);
    vec3 centre = vec3(sin(texCoord0.x) * horizontal, sin(texCoord0.y), -cos(texCoord0.x) * horizontal);
    float radius = degrees(acos(clamp(dot(direction, centre), -1.0, 1.0)));

    vec3 color = lite_rainbow_band((42.6 - radius) / 2.1);
    color += lite_rainbow_band((radius - 50.1) / 3.4) * 0.42;
    // Inside the main bow, the light the drops send back at smaller angles.
    color += vec3(0.12) * smoothstep(20.0, 40.5, radius) * (1.0 - step(40.5, radius));
    float horizon = smoothstep(-0.01, 0.08, direction.y);
    float over = lite_rainbow_over(lite_scene_distance());
    if (over <= 0.0) {
        discard;
    }
    fragColor = vec4(color, vertexColor.a * horizon * over * 0.42);
}
#elif defined(DEPTH_ONLY)
// The sparks' depth, after their light (texCoord0 as below): what is drawn later at their distance
// sorts against them. The glows write none, and a spark that has faded out writes none.
layout(location = 2) in vec3 cameraRelative;

void main() {
    if (texCoord0.x > 5.0 || length(texCoord0) >= 1.0 || vertexColor.a < 0.02) {
        discard;
    }
    fragColor = vec4(0.0);
}
#else
// Firework sparks: texCoord0 runs -1..1 across the quad. A bright core with a dimmer
// rim, in two steps. Firework glows run 9..11 instead: a soft round falloff in four steps.
//
// A show is a place in the world under the clouds' bases (render.feature.weather.FireworksShow),
// drawn after the clouds and before translucent terrain. The land in front hides it, the Dynamic
// clouds in front dim it by what they let through, and the air between dims it as it dims the
// far lights.
#include <minecraft:fog.glsl>
#include <litelights:skyfog/atmosphere.glsl>

layout(location = 2) in vec3 cameraRelative;

#ifdef CLOUD_COVER
// The Dynamic clouds as their composite laid them over the picture: light, and the distance to
// their front plus the share of what lies behind that shows through (volume_composite.fsh). Bound
// only for a camera over the clouds' floor, the one place cloud can stand between it and a burst.
uniform sampler2D CloudMarch;

layout(push_constant) uniform SkyEventsCover {
    vec4 Cover;   // x screen pixels per marched pixel; yzw unused
};

// The share of a burst that shows through the clouds here, from the four marched pixels round this
// one. With the camera over the floor and every burst under it, all the cloud along this line of
// sight lies in front of the burst.
float lite_cloud_through() {
    ivec2 size = textureSize(CloudMarch, 0);
    vec2 marched = gl_FragCoord.xy / Cover.x - 0.5;
    ivec2 corner = ivec2(floor(marched));
    vec2 blend = marched - vec2(corner);
    float through = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        vec4 tap = texelFetch(CloudMarch, clamp(corner + offset, ivec2(0), size - 1), 0);
        float weight = (offset.x == 1 ? blend.x : 1.0 - blend.x) * (offset.y == 1 ? blend.y : 1.0 - blend.y);
        through += min(fract(tap.a) / 0.998, 1.0) * weight;
    }
    return through;
}

// Whether the scene's depth here is the cloud's front, which the clouds write where they hide more
// than half of what is behind (volume_depth.fsh, from the same marched pixel), rather than land.
bool lite_scene_is_cloud(float sceneDistance) {
    ivec2 size = textureSize(CloudMarch, 0);
    vec4 tap = texelFetch(CloudMarch, clamp(ivec2(gl_FragCoord.xy / Cover.x), ivec2(0), size - 1), 0);
    float front = floor(tap.a);
    return front > 0.0 && fract(tap.a) < 0.5 && abs(sceneDistance - front) <= front * 0.01 + 1.0;
}
#endif

// How much of a burst at this distance shows past what stands in front of it.
float lite_firework_seen(float distance) {
    float sceneDistance = lite_scene_distance();
#ifdef CLOUD_COVER
    if (sceneDistance < distance && !lite_scene_is_cloud(sceneDistance)) {
        return 0.0;
    }
    return lite_cloud_through();
#else
    return sceneDistance < distance ? 0.0 : 1.0;
#endif
}

// A burst is far brighter than the land the haze is tuned for: the air takes the same share of
// its light, but it shows dimmer only once more than this share's inverse is gone (a show two
// kilometres off on a clear night loses about four fifths of its light to the haze; rain's storm
// fog takes nearly all of it).
const float LITE_FIREWORK_SHINE = 4.0;

// How much of the burst's light comes through the air between: LiteLights' haze, mist and storm
// fog, as the far lights take them (the air's own colour is in the scene behind already), or with
// vanilla's fog its environmental part, which closes in for rain, water and blindness.
float lite_firework_air(vec3 position) {
    float through;
    if (lite_feature(LITE_FEATURE_LITE_FOG)) {
        through = lite_feature(LITE_FEATURE_HAZE) ? 1.0 - lite_air(position, 1.0).a : 1.0;
    } else {
        through = 1.0 - linear_fog_value(length(position), FogEnvironmentalStart, FogEnvironmentalEnd);
    }
    return min(through * LITE_FIREWORK_SHINE, 1.0);
}

void main() {
    float alpha;
    if (texCoord0.x > 5.0) {
        float glow = 1.0 - smoothstep(0.0, 1.0, length(texCoord0 - 10.0));
        alpha = lite_steps(glow * glow, 4.0);
    } else {
        // A spark only a few pixels across is all core: its rim would be all a far one showed.
        float core = max(0.45, fwidth(texCoord0.x) * 0.9);
        float distance = length(texCoord0);
        alpha = distance < core ? 1.0 : (distance < 1.0 ? 0.35 : 0.0);
    }
    alpha *= vertexColor.a;
    if (alpha <= 0.0) {
        discard;
    }
    alpha *= lite_firework_seen(length(cameraRelative - lite_view_origin()));
    if (alpha <= 0.0) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, alpha * lite_firework_air(cameraRelative));
}
#endif
