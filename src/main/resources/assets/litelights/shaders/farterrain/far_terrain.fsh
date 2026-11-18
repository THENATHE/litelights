#version 330
#extension GL_ARB_separate_shader_objects : require

// The far-terrain pass (render.feature.farterrain.FarTerrainPass). Reads a copy of the target and
// its depth, and rewrites the colour of
//   - another mod's far terrain (Voxy in the main target, Distant Horizons in its own texture):
//     LiteLights' light model with far shadows, the night tone, the sea's water look, then the air;
//   - LiteLights' own terrain past the shadow map: far shadows only, under the fog it already has.
// Every other pixel is discarded. Only the colour is written; the main target's alpha (the
// emissive mask) stays as it is.

#include <litelights:farterrain/far_light.glsl>
#include <litelights:near_mark.glsl>

uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D LightmapSampler;
// Far shadows at half resolution: r visibility, g distance from the camera in blocks (for the
// depth-aware upsample). A 1 x 1 white texture when far shadows are off.
uniform sampler2D FarShadowSampler;
// The far mod's translucent pass, for finding far water (farterrain/far_water.glsl): Voxy's colour
// before (A) and after (B) it; Distant Horizons' depth before it (A). Unread without a capture.
uniform sampler2D FarWaterA;
uniform sampler2D FarWaterB;

#include <litelights:farterrain/far_water.glsl>
#ifdef LITE_FAR_SNOW
#include <litelights:farterrain/far_snow.glsl>
#endif

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// The far shadow at a pixel, from the four half-resolution texels around it. Each weighs in by
// its bilinear share and by how close its distance is to the pixel's, so a shadow never bleeds
// across a silhouette onto land far behind it.
float lite_far_shadow_at(vec2 uv, float distance) {
    vec2 texel = uv / FarDepthInfo.zw - 0.5;
    ivec2 base = ivec2(floor(texel));
    vec2 blend = texel - vec2(base);
    ivec2 last = textureSize(FarShadowSampler, 0) - 1;
    float total = 0.0;
    float weights = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        vec2 stored = texelFetch(FarShadowSampler, clamp(base + offset, ivec2(0), last), 0).rg;
        vec2 share = mix(1.0 - blend, blend, vec2(offset));
        float closeness = 1.0 / (abs(stored.g - distance) + 0.02 * distance + 0.5);
        float weight = share.x * share.y * closeness;
        total += stored.r * weight;
        weights += weight;
    }
    return weights > 0.0 ? total / weights : 1.0;
}

#ifndef LITE_FAR_SOURCE_DH
// A short screen-space reflection off the far sea against the copied scene: steps that grow with
// distance, accepted where the ray passes just behind a surface. Returns the colour, a = how sure
// (fading toward the screen edges), or a = 0.
vec4 lite_far_trace(vec3 origin, vec3 direction) {
    float travelled = 2.0;
    for (int i = 0; i < 24; i++) {
        vec3 point = origin + direction * travelled;
        vec4 clip = LiteViewProjection * vec4(point, 1.0);
        if (clip.w <= 0.05) {
            break;
        }
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
            break;
        }
        float depth = texture(SceneDepth, uv).r;
        if (lite_far_has_depth(depth)) {
            float seen = length(lite_far_position(uv, depth));
            float along = length(point);
            if (seen < along && along - seen < travelled * 0.35 + 2.0) {
                vec2 edge = min(uv, 1.0 - uv);
                return vec4(texture(SceneColor, uv).rgb, smoothstep(0.0, 0.08, min(edge.x, edge.y)));
            }
        }
        travelled *= 1.35;
    }
    return vec4(0.0);
}
#endif

// The water look on the far sea, over what the far mod drew there: the sky (and, in the main
// target, the land on screen) reflects at grazing angles, with the sun's glint. The far mod's own
// water colour stays: LiteLights' near water hands over to it at the border instead
// (lite_water_depth_look), so the two meet without a band.
vec3 lite_far_water(vec3 color, vec3 surface, float visibility) {
    bool realistic = lite_water(LITE_WATER_REALISTIC) || lite_water(LITE_WATER_ENHANCED);
    if (!lite_water(LITE_WATER_REFLECTIONS)) {
        return color;
    }
    float distance = length(surface);
    vec3 waveNormal = lite_water_wave_normal(lite_water_anchored(surface), LiteTime.y, distance);
    vec3 view = surface / distance;
    vec3 direction = reflect(view, waveNormal);
    direction.y = max(direction.y, 0.02);
    direction = normalize(direction);
    float facing = clamp(dot(-view, waveNormal), 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - facing, 5.0);
    float reflectance = min(fresnel * LiteWaterParams.x * (realistic ? 0.8 : 0.5), realistic ? 0.7 : 0.4);
    // Far shadows shade the far water's mirror as they do near water's.
    vec3 reflection = lite_water_shaded_sky(direction, lite_mirror_shade(visibility, 1.0));
    #ifndef LITE_FAR_SOURCE_DH
    vec4 hit = lite_far_trace(surface, direction);
    reflection = mix(reflection, hit.rgb, hit.a);
    #endif
    return mix(color, reflection, reflectance);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    if (!lite_far_has_depth(depth)) {
        discard;
    }
    vec4 scene = texelFetch(SceneColor, pixel, 0);
    vec3 position = lite_far_position(texCoord, depth);

    #ifdef LITE_FAR_SOURCE_DH
    bool farTerrain = true;
    #else
    // LiteLights' own opaque surfaces carry LITE_NEAR_ALPHA (or the emissive band); the far mod's
    // pixels don't.
    bool farTerrain = FarSwitches.x > 0.5 && scene.a > LITE_EMISSIVE_ALPHA_LIMIT && abs(scene.a - LITE_NEAR_ALPHA) > 0.5 / 255.0;
    #endif
    float shadowWeight = FarSwitches.z > 0.5 ? smoothstep(FarShadowRange.x, FarShadowRange.y, length(position.xz)) : 0.0;
    if (!farTerrain && shadowWeight <= 0.0) {
        discard;
    }

    float distance = length(position);
    vec3 normal = lite_far_normal(SceneDepth, pixel, position);
    float facing = lite_light_facing(normal);
    float visibility = shadowWeight > 0.0 ? mix(1.0, lite_far_shadow_at(texCoord, distance), shadowWeight) : 1.0;

    vec3 color = scene.rgb;
    if (farTerrain) {
        #ifdef LITE_FAR_SNOW
        // The snow variant finds the water first: snow goes on the land the far mod lit, before
        // LiteLights relights it, and is never painted on a lake's or the sea's floor (far_snow.glsl).
        #ifdef LITE_FAR_WATER_DETAILS
        bool wantsThickness = FarWaterSwitches.y > 0.5 || FarWaterSwitches.z > 0.5 || FarWaterSwitches.w > 0.5;
        #else
        bool wantsThickness = false;
        #endif
        LiteFarWater water = lite_far_find_water(pixel, texCoord, depth, position, normal, wantsThickness);
        color = lite_far_snow(color, LightmapSampler, position, normal, lite_far_footprint(texCoord, depth, position), water.found);
        if (FarSnowState.w > 0.5) {
            // The debug view: what the snow lines say, unlit and unfogged; water dark blue.
            fragColor = vec4(water.found ? vec3(0.1, 0.25, 0.7) : color, scene.a);
            return;
        }
        if (FarSwitches.y > 0.5) {
            color = lite_far_relight(color, LightmapSampler, visibility, facing);
        }
        #else
        if (FarSwitches.y > 0.5) {
            color = lite_far_relight(color, LightmapSampler, visibility, facing);
        }
        // Found whether or not its look is on: the air over water always ends at its surface.
        #ifdef LITE_FAR_WATER_DETAILS
        bool wantsThickness = FarWaterSwitches.y > 0.5 || FarWaterSwitches.z > 0.5 || FarWaterSwitches.w > 0.5;
        #else
        bool wantsThickness = false;
        #endif
        LiteFarWater water = lite_far_find_water(pixel, texCoord, depth, position, normal, wantsThickness);
        #endif
        #ifdef LITE_FAR_WATER_DETAILS
        if (water.found) {
            float footprint = lite_far_footprint(texCoord, depth, position);
            if (FarWaterSwitches.y > 0.5) {
                color = lite_far_caustics(color, water, footprint, visibility);
            }
            if (FarWaterSwitches.z > 0.5) {
                color = lite_far_depth_colour(color, water, lite_far_light(LightmapSampler, visibility, 1.0));
            }
            if (FarSwitches.w > 0.5) {
                color = lite_far_water(color, water.surface, visibility);
            }
            if (FarWaterSwitches.w > 0.5) {
                color = lite_far_edge_foam(color, pixel, water, footprint, lite_far_light(LightmapSampler, visibility, 1.0));
            }
        }
        #else
        if (water.found && FarSwitches.w > 0.5) {
            color = lite_far_water(color, water.surface, visibility);
        }
        #endif
        if (FarShadowRange.w > 0.5) {
            // Over water the air ends at its surface, not at the ground behind it, as it does for
            // LiteLights' own water.
            color = lite_far_fog(color, water.found ? water.surface : position);
        }
    } else {
        color = lite_far_shadow_own(color, LightmapSampler, position, visibility, facing);
    }
    fragColor = vec4(color, scene.a);
}
