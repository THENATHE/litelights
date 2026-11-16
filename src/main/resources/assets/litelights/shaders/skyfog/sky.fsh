#version 330
#extension GL_ARB_separate_shader_objects : require

// LiteLights' sky, drawn over vanilla's sky pass and before the terrain. Output is premultiplied
// (blended with ONE, ONE_MINUS_SRC_ALPHA): alpha 0 adds light over vanilla's sky (sun, moon,
// stars), alpha 1 replaces it (the Realistic sky, the horizon haze).

#include <minecraft:fog.glsl>
#include <litelights:frame.glsl>
#include <litelights:post_common.glsl>
#include <litelights:skyfog/atmosphere.glsl>
#include <litelights:skyfog/celestial.glsl>

layout(location = 0) in vec2 clipPosition;

layout(location = 0) out vec4 fragColor;

// Puts a colour over what is there so far, by coverage, in premultiplied form.
vec4 lite_over(vec4 below, vec3 color, float coverage) {
    return vec4(below.rgb * (1.0 - coverage) + color * coverage, below.a * (1.0 - coverage) + coverage);
}

// The Realistic sky: from the horizon colour (the haze, which carries the sunset) up to the
// zenith colour, with a tight bright ring around the sun. Below the horizon it darkens toward the
// ground's own shade of the haze.
vec3 lite_realistic_sky(vec3 direction) {
    vec3 horizon = lite_horizon_color(direction);
    float up = max(direction.y, 0.0);
    vec3 sky = mix(horizon, LiteSkyZenith.rgb, smoothstep(0.0, 1.0, pow(up, 0.55)));
    float towardSun = max(dot(direction, LiteSunDirection.xyz), 0.0);
    sky += LiteSunGlow.rgb * 0.5 * pow(towardSun, 48.0);
    if (direction.y < 0.0) {
        sky = horizon * mix(1.0, 0.6, smoothstep(0.0, -0.35, direction.y));
    }
    return sky;
}

// How much of the haze colour covers the sky near the horizon: all of it below, fading out by
// about 15 degrees up. Distant land fades to this same colour, so the two meet without a seam.
float lite_horizon_band(vec3 direction) {
    return exp(-max(direction.y, 0.0) * 9.0);
}

void main() {
    // The same line of sight the level is drawn with, view bobbing and nausea included.
    vec3 direction = lite_view_direction(clipPosition * 0.5 + 0.5);
    vec4 sky = vec4(0.0);
    bool liteFog = lite_feature(LITE_FEATURE_LITE_FOG);
    bool end = LiteCameraBlock.w == LITE_DIMENSION_END;

    if (lite_feature(LITE_FEATURE_REALISTIC_SKY)) {
        sky = vec4(liteFog ? lite_realistic_sky(direction) : mix(FogColor.rgb, LiteSkyZenith.rgb, smoothstep(0.0, 1.0, pow(max(direction.y, 0.0), 0.55))), 1.0);
    }

    if (lite_feature(LITE_FEATURE_LITE_SKY)) {
        // Rain hides the sky's lights, as it does in vanilla.
        float clear = 1.0 - LiteWeather.x;
        vec3 lights = lite_sun(direction) + lite_moon(direction);
        float starlight = LiteSkyZenith.a * clear;
        if (starlight > 0.0) {
            vec3 stars = lite_stars(direction);
            if (lite_feature(LITE_FEATURE_SHOOTING_STARS) && LiteTime.z <= 0.0) {
                stars += lite_shooting_star(direction);
            }
            // Stars don't show through the sun and moon squares.
            lights += stars * starlight * step(dot(lights, vec3(1.0)), 0.0);
        }
        // Below the horizon there is only ground.
        float aboveHorizon = smoothstep(-0.02, 0.01, direction.y);
        sky.rgb += lights * clear * aboveHorizon;
        if (lite_feature(LITE_FEATURE_SHOOTING_STARS) && LiteTime.z > 0.0) {
            // Previewed falling stars show even while it really rains, like the other sky events.
            sky.rgb += lite_shooting_star(direction) * max(LiteSkyZenith.a, 0.5) * aboveHorizon;
        }
    }

    if (end) {
        if (LiteEndTint.a > 0.0) {
            float azimuth = atan(direction.z, direction.x);
            float time = LiteTime.y;
            float height = direction.y - 0.12 - 0.05 * sin(azimuth * 2.0 + time * 0.03);
            float band = exp(-height * height / 0.032);
            float waves = 0.55 + 0.45 * sin(azimuth * 5.0 + time * 0.07 + 1.5 * sin(azimuth * 3.0 - time * 0.05));
            float pulse = 0.8 + 0.2 * sin(time * 0.4);
            vec3 aura = mix(vec3(0.42, 0.18, 0.62), vec3(0.16, 0.46, 0.55), 0.5 + 0.5 * sin(azimuth * 2.0 - time * 0.02));
            sky.rgb += aura * band * waves * pulse * LiteEndTint.a * 0.75;
        }
        if (liteFog && lite_feature(LITE_FEATURE_HAZE)) {
            sky = lite_over(sky, LiteHazeColor.rgb, 0.7 * exp(-abs(direction.y) * 6.0));
        }
    } else if (liteFog) {
        sky = lite_over(sky, lite_horizon_color(direction), lite_horizon_band(direction));
        // A storm hides the sky itself.
        sky = lite_over(sky, LiteStormFog.rgb, LiteSkyInfo.w);
    } else if (lite_feature(LITE_FEATURE_REALISTIC_SKY)) {
        // Vanilla fog: meet vanilla's fog colour at the horizon instead.
        sky = lite_over(sky, FogColor.rgb, lite_horizon_band(direction));
    }

    if (lite_feature(LITE_FEATURE_DARK_DISC) && direction.y < 0.0) {
        sky = lite_over(sky, vec3(0.0), smoothstep(0.0, -0.01, direction.y));
    }
    fragColor = sky;
}
