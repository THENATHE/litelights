#ifndef LITELIGHTS_LIGHT_POST_RAY_PATTERN_GLSL
#define LITELIGHTS_LIGHT_POST_RAY_PATTERN_GLSL

#include <litelights:frame.glsl>
#include <litelights:noise.glsl>

// Shared by the light-ray slices (in the air) and the surface hook (where the rays land), so a
// bright streak in the air lands as a bright streak on the ground.

// World position with the camera block wrapped to 1024, precise enough for patterns anywhere in
// the world (the pattern repeats every 1024 blocks).
vec3 lite_lp_pattern_position(vec3 cameraRelative) {
    return lite_block_local(cameraRelative) + vec3(LiteCameraBlock.xyz & ivec3(1023));
}

// Direction sunlight travels along: straight from the sun in air, steeper under water (refracted
// at the surface).
vec3 lite_lp_ray_direction(bool underwater) {
    vec3 towardSun = LiteSunDirection.xyz;
    if (!underwater) {
        return -towardSun;
    }
    vec3 refracted = refract(-towardSun, vec3(0.0, 1.0, 0.0), 1.0 / 1.33);
    return dot(refracted, refracted) > 0.0 ? normalize(refracted) : vec3(0.0, -1.0, 0.0);
}

// Brightness of the ray pattern at a point, around 1. The pattern is drawn on the plane across
// the rays, so it stretches along them into streaks. Under water it is a set of slowly moving
// shafts from the waves; in air a gentle variation, like dust.
float lite_lp_ray_pattern(vec3 cameraRelative, bool underwater) {
    vec3 along = lite_lp_ray_direction(underwater);
    vec3 side = normalize(cross(along, abs(along.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 across = cross(along, side);
    vec3 world = lite_lp_pattern_position(cameraRelative);
    vec2 plane = vec2(dot(world, side), dot(world, across));
    float time = LiteTime.y;
    if (underwater) {
        plane *= 0.45;
        float waves = lite_value_noise(plane + vec2(time * 0.21, time * 0.08)) * 0.6
            + lite_value_noise(plane * 2.3 - vec2(time * 0.17, -time * 0.29)) * 0.4;
        float shaft = waves * waves;
        return shaft * shaft * 7.0;
    }
    float dust = lite_value_noise(plane * 0.18 + vec2(time * 0.015, time * 0.006));
    return mix(0.55, 1.45, dust);
}

#endif
