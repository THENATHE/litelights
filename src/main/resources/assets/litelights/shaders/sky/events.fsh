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

void main() {
    float time = LiteTime.y;
    float along = texCoord0.x;
    // Rows and rays stay a few pixels wide however near or far a band is: a band overhead is cut
    // into more of them, a band on the horizon into fewer. Powers of two, so they don't swim.
    float rows = exp2(clamp(floor(log2(0.25 / max(fwidth(texCoord0.y), 1.0e-5))), 4.0, 10.0));
    float height = floor(clamp(texCoord0.y, 0.0, 1.0) * rows + 0.5) / rows;
    float group = exp2(clamp(ceil(log2(max(fwidth(along), 1.0e-5) * 3.0)), -3.0, 6.0));

    float ray = floor(along / group) * group;
    float flicker = 0.65 + 0.35 * sin(time * (0.3 + lite_sin_hash(ray) * 0.5) + lite_sin_hash(ray + 7.0) * 6.2831);
    float bundle = 0.55 + 0.45 * sin(along * 0.11 + lite_sin_hash(floor(along / 9.0)) * 6.2831 + time * 0.06);
    // A soft glow under the rays, as in photographs: not all the light is in the rays.
    float rayStrength = 0.3 + 0.7 * mix(0.45, 1.0, lite_sin_hash(ray + 3.0)) * flicker * bundle;
    float ripple = 0.75 + 0.25 * sin(along * 0.045 - time * 0.3);
    // How high this ray reaches before it fades.
    float reach = mix(0.45, 1.0, lite_sin_hash(ray + 11.0));

    float fringe = vertexColor.r * exp(-pow((height - 0.03) / 0.03, 2.0));
    float body = smoothstep(0.02, 0.07, height) * exp(-max(height - 0.07, 0.0) / (0.2 * reach));
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
#else
// Meteors and firework sparks: texCoord0 runs -1..1 across the quad. A bright core with a dimmer
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
