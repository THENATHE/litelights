#ifndef LITELIGHTS_WATER_REFLECTION_GLSL
#define LITELIGHTS_WATER_REFLECTION_GLSL

#include <litelights:frame.glsl>
#include <litelights:water/uniforms.glsl>

// Screen-space reflections against the opaque scene copied just before translucent terrain, and
// the sky and fog colour for rays that find nothing on screen. Only water and ice call these.

bool lite_scene_copy_valid() {
    return textureSize(LiteScene, 0).x > 1;
}

float lite_water_sky_visibility(vec2 lightCoord) {
    return smoothstep(0.35, 0.95, lightCoord.y / 240.0);
}

// The sky a reflected ray sees when it leaves the screen without hitting anything: the sky above
// the fog colour at the horizon.
vec3 lite_water_sky_colour(vec3 direction) {
    float up = clamp(direction.y, 0.0, 1.0);
    return mix(LiteWaterHorizonColor.rgb, LiteWaterSkyColor.rgb, smoothstep(0.02, 0.45, up) * LiteWaterSkyColor.a);
}

// The sun's reflection along a reflected ray.
vec3 lite_water_sun(vec3 direction) {
    float toSun = max(dot(direction, LiteSunDirection.xyz), 0.0);
    float sun = pow(toSun, 1400.0) * 6.0 + pow(toSun, 48.0) * 0.18;
    return LiteSunColor.rgb * sun * LiteSunColor.a * LiteWaterSkyColor.a * (1.0 - 0.85 * LiteWeather.x);
}

vec3 lite_water_sky(vec3 direction) {
    return lite_water_sky_colour(direction) + lite_water_sun(direction);
}

// Shade on a mirror (water, ice). Seen from near the surface water is mostly reflected sky, so
// unless the reflection is shaded too, a shadow all but vanishes there. A shaded spot reflects the
// sky with the share of sky light a shadow takes from land (Darkness and Extra darkness, the same
// ShadowDepth), cooler with the Relit model as land in shade is, and shows none of the sun's
// reflection. Objects the reflection finds on screen keep their own light. visibility is the
// shadow lookup (1 lit), facing how much the surface faces the light. Returns the sky's
// multiplier in rgb and the sun's in a.
vec4 lite_mirror_shade(float visibility, float facing) {
    float strength = LiteShadowParams.x * LiteShadowFade.y;
    if (!lite_world_pass() || !lite_feature(LITE_FEATURE_SHADOWS) || strength <= 0.0) {
        return vec4(1.0);
    }
    float missing = clamp((1.0 - visibility) * facing, 0.0, 1.0);
    float sky = 1.0 - LiteShadowParams.y * strength * missing;
    vec3 tint = vec3(1.0);
    if (lite_feature(LITE_FEATURE_ENHANCED_SHADOWS)) {
        tint = mix(vec3(1.0), LiteAmbientColor.rgb, missing * strength);
    }
    return vec4(tint * sky, 1.0 - missing);
}

// The sky and sun on a mirror, shaded (see lite_mirror_shade).
vec3 lite_water_shaded_sky(vec3 direction, vec4 shade) {
    return lite_water_sky_colour(direction) * shade.rgb + lite_water_sun(direction) * shade.a;
}

// Marches a ray across the screen from origin (camera-relative) along direction. Returns the
// scene colour where it passes behind an opaque surface, with a = confidence (fading out toward
// the screen edges), or a = 0 if it hits nothing.
//
// Depths are compared as view depths: the scene copy stores them, and the ray's comes from
// interpolating 1 / w, so each step costs one fetch and no matrix work. When a step goes from in front of the scene to behind
// it, the crossing is refined, and accepted only if the ray there is less than a block or two
// behind the surface, so rays passing behind thin things carry on.
vec4 lite_water_trace(vec3 origin, vec3 direction, float jitter, int steps, int refinements) {
    vec4 startClip = LiteViewProjection * vec4(origin, 1.0);
    vec4 endClip = LiteViewProjection * vec4(origin + direction * 512.0, 1.0);
    const float nearestW = 0.05;
    if (endClip.w < nearestW) {
        endClip = mix(startClip, endClip, (startClip.w - nearestW) / (startClip.w - endClip.w));
    }
    vec3 startNdc = startClip.xyz / startClip.w;
    vec3 endNdc = endClip.xyz / endClip.w;
    vec2 startPixel = (startNdc.xy * 0.5 + 0.5) * LiteScreen.xy;
    vec2 endPixel = (endNdc.xy * 0.5 + 0.5) * LiteScreen.xy;

    // Shorten the segment to where it leaves the screen, so no step is spent outside it.
    vec2 delta = endPixel - startPixel;
    vec2 limit = mix(vec2(0.5), LiteScreen.xy - 0.5, step(vec2(0.0), delta));
    float exitX = abs(delta.x) > 1.0e-4 ? (limit.x - startPixel.x) / delta.x : 1.0e9;
    float exitY = abs(delta.y) > 1.0e-4 ? (limit.y - startPixel.y) / delta.y : 1.0e9;
    float lastT = min(1.0, min(exitX, exitY));
    if (lastT <= 0.0) {
        return vec4(0.0);
    }

    float startInverseW = 1.0 / startClip.w;
    float endInverseW = 1.0 / endClip.w;
    float previousT = 0.0;
    bool wasInFront = true;
    for (int i = 1; i <= steps; i++) {
        // Steps bunch up near the start, where the ray needs the most detail.
        float s = (float(i) - jitter) / float(steps);
        float t = s * s * lastT;
        vec2 pixel = mix(startPixel, endPixel, t);
        // 1 / w is affine along a screen-space segment, so the ray's view depth costs one division.
        float rayW = 1.0 / mix(startInverseW, endInverseW, t);
        bool behind = rayW > texelFetch(LiteScene, ivec2(pixel), 0).a;
        if (behind && wasInFront) {
            // The ray crossed a surface between the last step and this one: home in on the
            // crossing. A long step at a grazing angle can land far behind a wall it really hit,
            // so the thickness test happens at the crossing, not at the step.
            float near = previousT;
            float far = t;
            for (int r = 0; r < refinements; r++) {
                float middle = (near + far) * 0.5;
                vec2 middlePixel = mix(startPixel, endPixel, middle);
                if (1.0 / mix(startInverseW, endInverseW, middle) > texelFetch(LiteScene, ivec2(middlePixel), 0).a) {
                    far = middle;
                } else {
                    near = middle;
                }
            }
            vec2 hitPixel = mix(startPixel, endPixel, far);
            vec4 scene = texelFetch(LiteScene, ivec2(hitPixel), 0);
            float hitW = 1.0 / mix(startInverseW, endInverseW, far);
            if (hitW - scene.a < 1.5 + scene.a * 0.08) {
                vec2 edge = min(hitPixel, LiteScreen.xy - hitPixel) / (LiteScreen.xy * 0.06);
                return vec4(scene.rgb, clamp(min(edge.x, edge.y), 0.0, 1.0));
            }
        }
        wasInFront = !behind;
        previousT = t;
    }
    return vec4(0.0);
}

#endif
