#ifndef LITELIGHTS_SKYFOG_ATMOSPHERE_GLSL
#define LITELIGHTS_SKYFOG_ATMOSPHERE_GLSL

// The air between the camera and what it sees: haze, ground mist, storm fog and the colour the
// sky has at the horizon. Shared by the fog hook, the sky pass and the clouds, so distant land,
// clouds and the sky meet in exactly the same colour. Values come from render.feature.skyfog.SkyFogFeature;
// FogShaderMirror in the unit tests (src/test/.../render/feature/skyfog) mirrors the maths.

#include <litelights:frame.glsl>
#include <litelights:noise.glsl>
#include <litelights:skyfog/lightning.glsl>

// Ground mist at full amount: optical depth per block at ground level, and how fast it thins with
// height (it halves about every four blocks).
const float LITE_MIST_DENSITY = 0.011;
const float LITE_MIST_FALLOFF = 0.17;
// Wisps: the mist is patchy at this scale (blocks) and drifts at this speed (blocks per second).
const float LITE_WISP_SCALE = 16.0;
const vec2 LITE_WISP_DRIFT = vec2(0.55, 0.21);
// Positions are wrapped every 2048 blocks (a multiple of both noise scales, 16 and 6.4), so world
// coordinates far from the origin keep their precision. A power of two, so the wrap is a bit mask
// that is exact for negative coordinates too.
const int LITE_WISP_WRAP_MASK = 2047;

// The haze's colour seen in a direction: its own colour, brighter and warmer toward the sun.
vec3 lite_haze_color(vec3 direction) {
    vec3 color = LiteHazeColor.rgb;
    if (!lite_feature(LITE_FEATURE_SKYLESS)) {
        float toward = max(dot(direction, LiteSunDirection.xyz), 0.0);
        color += LiteSunGlow.rgb * pow(toward, LiteSunGlow.a);
    }
    return color;
}

// What the sky looks like right at the horizon in a direction. Land at the edge of the render
// distance fades to exactly this, so it disappears into the sky without a seam. A lightning flash
// lights it for a moment.
vec3 lite_horizon_color(vec3 direction) {
    return mix(lite_haze_color(direction), LiteStormFog.rgb, LiteSkyInfo.w) + lite_flash_air(direction);
}

// Ground mist is the haze, a little paler.
vec3 lite_mist_color() {
    return LiteHazeColor.rgb + vec3(LiteFogRange.w);
}

// Optical depth of an exponential height haze from the camera to a camera-relative position.
// Below its base the haze stops thickening.
//
// The integral is (exp(-f start) - exp(-f end)) / (f (end - start)), written so that both
// exponentials are at most 1. Written as exp(-f start) (1 - exp(-f (end - start))) / (f (end -
// start)), looking down from high up made the second factor overflow while the first underflowed:
// with denormals flushed to zero (RADV on Vulkan) that was 0 times infinity, NaN, and far land seen
// from about y 700 up was black.
float lite_exponential_depth(vec3 position, float distance, float cameraHeight, float density, float falloff) {
    float startHeight = max(cameraHeight, 0.0);
    float endHeight = max(cameraHeight + position.y, 0.0);
    float exponent = falloff * (endHeight - startHeight);
    float atStart = exp(-falloff * startHeight);
    float shape = abs(exponent) > 1.0e-3 ? (atStart - exp(-falloff * endHeight)) / exponent : atStart * (1.0 - 0.5 * exponent);
    return density * distance * shape;
}

float lite_haze_depth(vec3 position, float distance) {
    return lite_exponential_depth(position, distance, LiteHazeShape.y, LiteHazeColor.a, LiteHazeShape.x);
}

// Patchy, drifting mist: a two-octave noise on the ground plane at the point seen. Far away the
// patches would be smaller than a pixel, so they average out to an even mist.
float lite_wisps(vec3 position, float distance) {
    vec2 world = vec2(LiteCameraBlock.xz & LITE_WISP_WRAP_MASK) + lite_block_local(position).xz;
    vec2 drift = LITE_WISP_DRIFT * LiteTime.y;
    float coarse = lite_value_noise((world + drift) / LITE_WISP_SCALE);
    float fine = lite_value_noise((world - drift * 1.7) / (LITE_WISP_SCALE * 0.4));
    float patches = smoothstep(0.25, 0.85, coarse * 0.65 + fine * 0.35);
    float detail = 1.0 - smoothstep(48.0, 160.0, distance);
    return mix(1.0, mix(0.35, 1.7, patches), detail);
}

float lite_mist_depth(vec3 position, float distance) {
    float depth = lite_exponential_depth(position, distance, LiteHazeShape.w, LITE_MIST_DENSITY * LiteHazeShape.z, LITE_MIST_FALLOFF);
    // No mist right in front of the camera: it starts a few blocks out.
    return depth * smoothstep(3.0, 20.0, distance) * lite_wisps(position, distance);
}

// Storm fog is thin close to the eye and reaches its full density over the first dozen blocks, so a
// heavy storm's snow, sand and rain show around the player while the distance closes in (fog098).
// Past about 50 blocks it hides as much as an even fog did; SkyFogMath.stormDepth mirrors it.
const float LITE_STORM_CLEAR = 12.0;
const float LITE_STORM_FAR_GAIN = 1.3;

float lite_storm_depth(float distance) {
    return LiteStormFog.a * LITE_STORM_FAR_GAIN * (distance - LITE_STORM_CLEAR * (1.0 - exp(-distance / LITE_STORM_CLEAR)));
}

// The render-distance fade: cylindrical like vanilla's, over the last quarter of the render
// distance, easing in so the edge of the loaded land has no visible start.
float lite_edge_fog(vec3 position) {
    float cylindrical = max(length(position.xz), abs(position.y));
    return smoothstep(LiteFogRange.x, max(LiteFogRange.y, LiteFogRange.x + 1.0), cylindrical);
}

// Haze and mist only glow where daylight reaches the air. With the camera outdoors the air in
// front of everything is lit, even in front of a cave mouth or a lake bed; with the camera in a
// cave (LiteFogRange.z, its sky exposure) only what still gets sky light sees lit air, so fog in
// a cave darkens what it hides instead of glowing. skyLight is the sky light there, 0..1.
float lite_air_light(float skyLight) {
    if (lite_feature(LITE_FEATURE_SKYLESS)) {
        return 1.0;
    }
    return mix(0.06, 1.0, max(smoothstep(0.0, 0.6, skyLight), LiteFogRange.z));
}

// The haze, ground mist and storm fog between the camera and a point (camera-relative): rgb is the
// colour they scatter, the depth-weighted mix of their colours, and a how much of the point they
// hide. Their optical depths add up. The caller checks LITE_FEATURE_HAZE.
vec4 lite_air(vec3 position, float skyLight) {
    float distance = max(length(position), 1.0e-3);
    float haze = lite_haze_depth(position, distance);
    float mist = lite_feature(LITE_FEATURE_GROUND_MIST) ? lite_mist_depth(position, distance) : 0.0;
    float storm = lite_feature(LITE_FEATURE_STORM_FOG) ? lite_storm_depth(distance) : 0.0;
    float total = haze + mist + storm;
    if (total <= 1.0e-5) {
        return vec4(0.0);
    }
    vec3 scattered = (haze * lite_haze_color(position / distance) + mist * lite_mist_color() + storm * LiteStormFog.rgb) / total;
    scattered += lite_flash_air(position / distance);
    return vec4(scattered * lite_air_light(skyLight), 1.0 - exp(-total));
}

#endif
