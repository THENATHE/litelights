#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:post_common.glsl>

// The one full-resolution tonemap pass: bloom added as light, then the colour grade, then the
// tone curve (the highlight roll-off, or one of the film curves), back to sRGB with alpha 1 (which
// also clears bloom's emissive mask). With grading off, the Vanilla-friendly curve and no bloom
// near a pixel, the pixel comes out exactly as it went in. The grade's and curves' steps match
// ColourGradeMath.applyGrade and applyCurve; change both together.

uniform sampler2D SceneSampler;
uniform sampler2D BloomNearSampler;
uniform sampler2D BloomWideSampler;
uniform sampler2D HandDepthSampler;   // the hand's depth (FLAG_HAND), cleared to HAND_DEPTH_CLEAR where no hand

layout(push_constant) uniform ToneAndGrade {
    vec4 MixRed;      // colour matrix row 0, w exposure multiplier
    vec4 MixGreen;    // row 1, w contrast exponent
    vec4 MixBlue;     // row 2, w contrast pivot (mid grey)
    vec4 Tones;       // x shadows, y midtones exponent, z highlights, w saturation multiplier
    vec4 Extra;       // x vibrance, y brightest graded value (roll-off off at 1), z bloom strength, w flags
    vec4 Curve;       // x exposure, y highlight expansion, z 1 / the curve's white, w blend with the plain picture
};

const int FLAG_GRADE = 1;
const int FLAG_BLOOM = 2;
const int FLAG_SHOW_BLOOM = 4;   // debugging: the bloom texture alone (-Dlitelights.debug.showBloom=true)
const int FLAG_SHOW_ALPHA = 8;   // debugging: the level's alpha (-Dlitelights.debug.showAlpha=true)
// Bits 4 and 5 of the flags: the tone curve (0 Vanilla-friendly, 1 ACES-style, 2 AgX-style, 3 Hable filmic).
const int CURVE_ACES = 1;
const int CURVE_AGX = 2;
const int CURVE_HABLE = 3;
const int FLAG_HAND = 64;        // bit 6: a hand was drawn this frame, HandDepthSampler is its depth
const float HAND_DEPTH_CLEAR = 0.0;   // BloomPass.HAND_DEPTH_CLEAR

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

float tones(float luma) {
    float p = lite_linear_to_srgb(luma);
    if (p < 1.0) {
        float q = 1.0 - p;
        p += Tones.x * 0.95 * p * q * q * q;
        p += Tones.z * 0.9 * p * p * q;
    }
    p = pow(max(p, 0.0), Tones.y);
    return lite_srgb_to_linear(p);
}

float rolloff(float value) {
    const float knee = 0.8;
    float maxInput = Extra.y;
    if (maxInput <= 1.0 || value <= knee) {
        return value;
    }
    float span = 1.0 - knee;
    float t = (value - knee) / span;
    float s = (maxInput - knee) / span;
    return knee + span * (t * (1.0 + t / (s * s)) / (1.0 + t));
}

// Stretches the brightest tones back out before a film curve: identity near black, white / (1 - y) at white.
vec3 expand(vec3 value, float white) {
    vec3 clamped = clamp(value, vec3(0.0), vec3(white));
    return clamped / (1.0 - Curve.y * clamped / white);
}

// Narkowicz's fit of the ACES film curve.
vec3 aces(vec3 x) {
    return x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14);
}

// Hable's filmic curve (A 0.15, B 0.50, C 0.10, D 0.20, E 0.02, F 0.30), without its own white scale.
vec3 hable(vec3 x) {
    const float A = 0.15;
    const float B = 0.50;
    const float C = 0.10;
    const float D = 0.20;
    const float E = 0.02;
    const float F = 0.30;
    return (x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F) - E / F;
}

// AgX: inset matrix, log2 encoding over -12.47 to +4.03 stops, the default contrast curve (sixth-order
// fit), outset matrix, then back to linear with the 2.2 display power. Matrices are column-major.
vec3 agx(vec3 color) {
    const mat3 inset = mat3(
        0.842479062, 0.042328242, 0.042375655,
        0.078433600, 0.878468636, 0.078433600,
        0.079223745, 0.079166127, 0.879142974);
    const mat3 outset = mat3(
        1.196879005, -0.052896852, -0.052971636,
        -0.098020881, 1.151903130, -0.098043450,
        -0.099029744, -0.098961177, 1.151073673);
    const float minEv = -12.47393;
    const float maxEv = 4.026069;
    vec3 x = (clamp(log2(max(inset * color, vec3(1.0e-10))), minEv, maxEv) - minEv) / (maxEv - minEv);
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    vec3 curved = 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4 - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
    return pow(max(outset * curved, vec3(0.0)), vec3(2.2));
}

vec3 toneCurve(vec3 color, int curve) {
    float white = max(Extra.y, 1.0);
    vec3 plain = vec3(rolloff(color.r), rolloff(color.g), rolloff(color.b));
    if (curve == 0) {
        return plain;
    }
    vec3 scene = Curve.x * expand(color, white);
    vec3 curved = curve == CURVE_ACES ? aces(scene) : curve == CURVE_AGX ? agx(scene) : hable(scene);
    return mix(plain, clamp(curved * Curve.z, 0.0, 1.0), Curve.w);
}

// Interleaved gradient noise (Jimenez 2014), -0.5 to 0.5: hides banding where a curve stretches tones.
float dither(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715)))) - 0.5;
}

vec3 grade(vec3 color) {
    color *= MixRed.w;
    color = max(vec3(dot(MixRed.xyz, color), dot(MixGreen.xyz, color), dot(MixBlue.xyz, color)), vec3(0.0));

    float pivot = MixBlue.w;
    color = pivot * pow(color / pivot, vec3(MixGreen.w));

    float luma = lite_luma(color);
    float toned = tones(luma);
    color = luma > 1.0e-5 ? color * (toned / luma) : vec3(toned);

    luma = lite_luma(color);
    float peak = max(color.r, max(color.g, color.b));
    float chroma = peak > 1.0e-5 ? (peak - min(color.r, min(color.g, color.b))) / peak : 0.0;
    float colourfulness = Tones.w * (1.0 + Extra.x * (1.0 - chroma));
    return max(vec3(luma) + (color - vec3(luma)) * colourfulness, vec3(0.0));
}

void main() {
    vec4 sceneWithAlpha = texture(SceneSampler, texCoord);
    vec3 scene = sceneWithAlpha.rgb;
    int flags = int(Extra.w + 0.5);
    if ((flags & FLAG_SHOW_ALPHA) != 0) {
        float alpha = sceneWithAlpha.a;
        fragColor = vec4(alpha < LITE_EMISSIVE_ALPHA_LIMIT ? vec3(1.0, 0.2, 0.0) : vec3(alpha * 0.5), 1.0);
        return;
    }
    vec3 color = lite_srgb_to_linear(scene);
    if ((flags & FLAG_SHOW_BLOOM) != 0) {
        vec3 bloom = texture(BloomNearSampler, texCoord).rgb * 0.6 + texture(BloomWideSampler, texCoord).rgb * 0.4;
        fragColor = vec4(lite_linear_to_srgb(min(bloom * 4.0, vec3(1.0))), 1.0);
        return;
    }
    // The level's glow stays behind a held item: a hand pixel only blooms if it marks a glowing texel itself.
    bool handCovers = (flags & FLAG_HAND) != 0 && texture(HandDepthSampler, texCoord).r != HAND_DEPTH_CLEAR
        && sceneWithAlpha.a >= LITE_EMISSIVE_ALPHA_LIMIT;
    if ((flags & FLAG_BLOOM) != 0 && !handCovers) {
        vec3 bloom = (texture(BloomNearSampler, texCoord).rgb * 0.6 + texture(BloomWideSampler, texCoord).rgb * 0.4) * Extra.z;
        color += clamp(bloom, 0.0, 1.0) * (1.0 - color);
    }
    if ((flags & FLAG_GRADE) != 0) {
        color = grade(color);
    }
    int curve = (flags >> 4) & 3;
    color = toneCurve(color, curve);
    vec3 encoded = lite_linear_to_srgb(color);
    if (curve != 0) {
        encoded = clamp(encoded + dither(gl_FragCoord.xy) / 255.0, 0.0, 1.0);
    }
    fragColor = vec4(encoded, 1.0);
}
