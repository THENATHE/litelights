#ifndef LITELIGHTS_SKYFOG_LIGHTNING_GLSL
#define LITELIGHTS_SKYFOG_LIGHTNING_GLSL

// Lightning's light (storm099): flashes inside the clouds, and what they throw on the air, the sky
// and the land. The values come from render.feature.lightning.CloudLightning (LiteLightning in the
// LiteFrame block). Outside a thunderstorm they are all zero, and each function stops at its first
// comparison. Two flashes at most, written out by hand: no loops.

#include <litelights:frame.glsl>
#include <litelights:noise.glsl>

// A flash is a cold blue-white.
const vec3 LITE_FLASH_COLOR = vec3(0.70, 0.76, 1.0);
// The glow fades this fast above and below the flash (blocks), so the higher cloud layer is lit less.
const float LITE_FLASH_DEPTH = 40.0;
// The cloud glow is patchy at this scale (blocks): a flash lights the cloud unevenly from within.
const float LITE_FLASH_PATCHES = 28.0;
// The glow in the cloud reaches a little past the flash's radius; the sky's halo around it less far.
const float LITE_FLASH_SPREAD = 1.25;
const float LITE_FLASH_HALO = 1.1;

// The storm fog tints the light it scatters: a haboob's dust turns it amber, a blizzard keeps it white.
vec3 lite_flash_tint() {
    float strongest = max(max(LiteStormFog.r, LiteStormFog.g), max(LiteStormFog.b, 1.0e-3));
    return mix(LITE_FLASH_COLOR, LiteStormFog.rgb / strongest, LiteSkyInfo.w * 0.6);
}

// What the flashes add to the air seen in a direction: a little all round, most toward them.
vec3 lite_flash_air(vec3 direction) {
    float strength = LiteLightning[0].w;
    if (strength <= 0.0) {
        return vec3(0.0);
    }
    float toward = max(dot(direction, LiteLightning[0].xyz), 0.0);
    return lite_flash_tint() * strength * (0.2 + 0.8 * toward * toward);
}

// How much brighter a surface gets for a moment: only where the sky reaches it, and faces turned
// toward the flashes more than those turned away. skyLight is the sky light there, 0..1.
float lite_flash_land(vec3 normal, float skyLight) {
    float gain = LiteLightning[3].z;
    if (gain <= 0.0) {
        return 0.0;
    }
    float facing = 0.45 + 0.55 * max(dot(normal, LiteLightning[0].xyz), 0.0);
    return gain * skyLight * skyLight * facing;
}

// One flash's glow on the cloud at a camera-relative position: brightest over its centre, gone a
// little past its radius.
float lite_flash_patch(vec4 flash, float radius, vec3 position) {
    if (flash.w <= 0.0) {
        return 0.0;
    }
    vec2 offset = (position.xz - flash.xz) / max(radius * LITE_FLASH_SPREAD, 1.0);
    return flash.w * exp(-dot(offset, offset)) * exp(-abs(position.y - flash.y) / LITE_FLASH_DEPTH);
}

// The light the flashes give the cloud at a camera-relative position.
vec3 lite_flash_cloud(vec3 position) {
    float glow = lite_flash_patch(LiteLightning[1], LiteLightning[3].x, position)
        + lite_flash_patch(LiteLightning[2], LiteLightning[3].y, position);
    if (glow <= 0.0) {
        return vec3(0.0);
    }
    vec2 world = vec2(LiteCameraBlock.xz & 2047) + lite_block_local(position).xz;
    float patches = mix(0.45, 1.15, lite_value_noise(world / LITE_FLASH_PATCHES));
    return lite_flash_tint() * glow * patches;
}

// The sky's glow around one flash, seen in a direction: the flash lights the cloud deck over an
// angle that shrinks with distance, so a far one is a small glow low over the horizon.
float lite_flash_halo(vec4 flash, float radius, vec3 direction) {
    if (flash.w <= 0.0) {
        return 0.0;
    }
    float distance = max(length(flash.xyz), 1.0);
    float size = clamp(LITE_FLASH_HALO * radius / distance, 0.03, 1.5);
    // 1 - cos(angle) is about half the angle squared.
    float away = 1.0 - dot(direction, flash.xyz / distance);
    return flash.w * exp(-2.0 * away / (size * size));
}

// What the flashes add to the sky seen in a direction (above the horizon only).
vec3 lite_flash_sky(vec3 direction) {
    float scale = LiteLightning[3].w;
    if (scale <= 0.0) {
        return vec3(0.0);
    }
    float glow = lite_flash_halo(LiteLightning[1], LiteLightning[3].x, direction)
        + lite_flash_halo(LiteLightning[2], LiteLightning[3].y, direction);
    return lite_flash_tint() * glow * scale * smoothstep(-0.06, 0.04, direction.y);
}

#endif
