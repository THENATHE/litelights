#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:frame.glsl>

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
// A rainbow after the rain (idea by AppleNButter, You Must Have The Rain). Its light is added to
// the sky. texCoord0 holds the point opposite the sun (its azimuth from north turning east, and its
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
    fragColor = vec4(color, vertexColor.a * horizon * 0.42);
}
#else
// Firework sparks: texCoord0 runs -1..1 across the quad. A bright core with a dimmer
// rim, in two steps. Firework glows run 9..11 instead: a soft round falloff in four steps.
void main() {
    if (texCoord0.x > 5.0) {
        float distance = length(texCoord0 - 10.0);
        float glow = 1.0 - smoothstep(0.0, 1.0, distance);
        fragColor = vec4(vertexColor.rgb, vertexColor.a * lite_steps(glow * glow, 4.0));
        return;
    }
    float distance = length(texCoord0);
    float shape = distance < 0.45 ? 1.0 : (distance < 1.0 ? 0.35 : 0.0);
    if (shape <= 0.0) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, vertexColor.a * shape);
}
#endif
