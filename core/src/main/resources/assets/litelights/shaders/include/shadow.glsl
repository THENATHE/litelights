#ifndef LITELIGHTS_SHADOW_GLSL
#define LITELIGHTS_SHADOW_GLSL

#include <litelights:frame.glsl>
#include <litelights:pixel_lock.glsl>

// The cached sun shadow map, the map being faded out after a swap, and the per-frame mob map near
// the player. All three are depth-only and hold standard (not reversed) depth.
uniform sampler2D LiteSunShadow;
uniform sampler2D LiteSunShadowPrev;
uniform sampler2D LiteMobShadow;

// Flat-shaded face normal from the position's screen derivatives, turned toward the camera. Terrain
// and block-model vertices carry no normal LiteLights can use, and their faces are flat, so this is
// exact. Here because this file is fragment-only, like the derivatives.
vec3 lite_face_normal(vec3 position) {
    vec3 normal = normalize(cross(dFdx(position), dFdy(position)));
    return dot(normal, position) > 0.0 ? -normal : normal;
}

float lite_shadow_depth_from_ndc(float ndcZ) {
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    return ndcZ;
#else
    return ndcZ * 0.5 + 0.5;
#endif
}

// 1 = lit, 0 = in shadow. Four depth comparisons blended bilinearly: a soft one-texel edge for the
// price of four fetches. Outside the map everything counts as lit, fading in over edgeFade (in
// texture space) so the map's border never shows as a line.
//
// A point deeper along the light than the map reaches (the land far below when flying high: the
// creature map only reaches 192 blocks either side of the camera) is held at the far plane, so it
// is shadowed by what the map holds in front of it and lit where the map is empty. Unclamped it lay
// behind the cleared depth and the whole map's square read as shadow on the land.
float lite_shadow_lookup(sampler2D map, mat4 toShadowClip, vec3 cameraRelative, float mapSize, float edgeFade) {
    vec3 ndc = (toShadowClip * vec4(cameraRelative, 1.0)).xyz;
    vec2 uv = ndc.xy * 0.5 + 0.5;
    vec2 edgeDistance = min(uv, 1.0 - uv);
    float coverage = smoothstep(0.0, edgeFade, min(edgeDistance.x, edgeDistance.y));
    if (coverage <= 0.0) {
        return 1.0;
    }
    float depth = min(lite_shadow_depth_from_ndc(ndc.z), 1.0);

    vec2 texel = uv * mapSize - 0.5;
    ivec2 base = ivec2(floor(texel));
    vec2 blend = texel - vec2(base);
    ivec2 lastTexel = ivec2(mapSize) - 1;
    float lit00 = step(depth, texelFetch(map, clamp(base, ivec2(0), lastTexel), 0).r);
    float lit10 = step(depth, texelFetch(map, clamp(base + ivec2(1, 0), ivec2(0), lastTexel), 0).r);
    float lit01 = step(depth, texelFetch(map, clamp(base + ivec2(0, 1), ivec2(0), lastTexel), 0).r);
    float lit11 = step(depth, texelFetch(map, clamp(base + ivec2(1, 1), ivec2(0), lastTexel), 0).r);
    float lit = mix(mix(lit00, lit10, blend.x), mix(lit01, lit11, blend.x), blend.y);
    return mix(1.0, lit, coverage);
}

// Moves the lookup point off the surface so a surface never shadows itself: along the normal by the
// Shadow offset setting (0.5 to 2 texels, 1.5 by default, LiteShadowFade.w), and a little toward the
// light. Scaled by the map's texel size, so the bias stays right at every detail tier.
vec3 lite_shadow_offset(vec3 cameraRelative, vec3 normal, float blocksPerTexel) {
    vec3 light = LiteShadowLightDirection.xyz;
    float facing = dot(normal, light);
    // Faces turned away from the light are in shadow anyway; nudging them toward the light keeps
    // their edges from flickering where they meet lit faces.
    vec3 towardLight = light * blocksPerTexel * (facing < 0.0 ? 1.5 : 0.5);
    return cameraRelative + normal * blocksPerTexel * LiteShadowFade.w + towardLight;
}

float lite_sun_shadow(vec3 cameraRelative, vec3 normal) {
    if (!lite_feature(LITE_FEATURE_SHADOWS) || !lite_feature(LITE_FEATURE_SUN_MAP)) {
        return 1.0;
    }
    vec3 position = lite_shadow_offset(cameraRelative, normal, LiteShadowMapInfo.y);
    float lit = lite_shadow_lookup(LiteSunShadow, LiteSunShadowMatrix, position, LiteShadowMapInfo.x, 0.02);
    if (lite_feature(LITE_FEATURE_SUN_MAP_FADING)) {
        float previous = lite_shadow_lookup(LiteSunShadowPrev, LiteSunShadowMatrixPrev, position, LiteShadowMapInfo.x, 0.02);
        lit = mix(previous, lit, LiteShadowFade.x);
    }
    // Shadows thin out over the last tenth of the shadow distance instead of stopping at a line.
    float distanceFade = smoothstep(LiteShadowParams.w * 0.9, LiteShadowParams.w, length(cameraRelative.xz));
    return mix(lit, 1.0, distanceFade);
}

float lite_mob_shadow(vec3 cameraRelative, vec3 normal) {
    if (!lite_feature(LITE_FEATURE_MOB_SHADOWS) || !lite_feature(LITE_FEATURE_MOB_MAP)) {
        return 1.0;
    }
    vec3 position = lite_shadow_offset(cameraRelative, normal, LiteShadowMapInfo.w);
    return lite_shadow_lookup(LiteMobShadow, LiteMobShadowMatrix, position, LiteShadowMapInfo.z, 0.1);
}

// The combined visibility of the sun (or moon): the darker of the cached terrain map and the mob
// map. pixelLockedPosition is the position after pixel lock, or the plain position when the lock
// is off (see lite_shadow_position).
float lite_shadow_visibility(vec3 pixelLockedPosition, vec3 normal) {
    return min(lite_sun_shadow(pixelLockedPosition, normal), lite_mob_shadow(pixelLockedPosition, normal));
}

// The position shadows are looked up at: snapped to the texel grid when pixel lock is on for
// shadows. Call from uniform control flow.
vec3 lite_shadow_position(vec3 cameraRelative, vec3 normal, vec2 uv, vec2 textureTexels) {
    vec3 locked = lite_pixel_lock_surface(cameraRelative, normal, uv, textureTexels);
    return lite_locked(LITE_LOCK_SHADOWS) ? locked : cameraRelative;
}

#endif
