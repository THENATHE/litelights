#ifndef LITELIGHTS_WATER_SURFACE_GLSL
#define LITELIGHTS_WATER_SURFACE_GLSL

#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:pixel_lock.glsl>
#include <litelights:surface.glsl>
#include <litelights:water/uniforms.glsl>
#include <litelights:water/common.glsl>
#include <litelights:water/reflection.glsl>
#include <litelights:water/large_textures.glsl>
#include <litelights:water/waves.glsl>
#include <litelights:terrain_parts.glsl>

// Water, lava and ice surfaces, and caustics on blocks under water. Called from the terrain and
// block-model fragment shaders after lighting, before fog (see hooks/water.glsl). Mac
// troubleshooting can compile the surfaces (LITE_TERRAIN_WATER) and the caustics and swash
// (LITE_TERRAIN_CAUSTICS) out of lite_water_surface (terrain_parts.glsl).

const vec3 LITE_FOAM_COLOUR = vec3(0.90, 0.95, 1.0);

// Four long-crested waves. Their wave vectors are whole multiples of 2*pi/2048, so the pattern
// repeats exactly with the 2048-block anchor (see lite_water_anchored).
// distance is how far the surface is from the camera: farther water calms down, as ripples smaller
// than a pixel would only turn the reflection into noise.
vec3 lite_water_wave_normal(vec2 p, float time, float distance) {
    const float K = 6.28318530718 / 2048.0;
    const vec2 WAVES[4] = vec2[4](vec2(310.0, 140.0), vec2(-190.0, 370.0), vec2(430.0, -260.0), vec2(90.0, 520.0));
    const float SPEEDS[4] = float[4](1.1, 1.4, 1.9, 2.3);
    float calm = 1.0 / (1.0 + distance / 20.0);
    float amplitude = (0.045 + 0.04 * LiteWeather.x) * calm;
    vec2 slope = vec2(0.0);
    for (int i = 0; i < 4; i++) {
        vec2 k = WAVES[i] * K;
        float phase = dot(p, k) + time * SPEEDS[i];
        slope += normalize(k) * cos(phase) * amplitude;
        amplitude *= 0.8;
    }
    // A little fine chop from noise, so the waves never look like a printed pattern.
    float chopX = lite_water_value_noise(p * 2.0 + vec2(time * 0.6, 0.0)) - 0.5;
    float chopZ = lite_water_value_noise(p * 2.0 + vec2(17.0, time * 0.5)) - 0.5;
    slope += vec2(chopX, chopZ) * 0.05 * calm;
    return normalize(vec3(-slope.x, 1.0, -slope.y));
}

// Small pixel-art leaves floating on the water under trees. Each half-block cell may hold one leaf
// a few texels across, drifting a texel or so around its spot. Returns 1 on a leaf texel.
const int LITE_LEAF_SHAPES[4] = int[4](1293208, 8601062, 490944, 407744);
const vec3 LITE_LEAF_COLOURS[4] = vec3[4](vec3(0.22, 0.36, 0.11), vec3(0.32, 0.47, 0.13), vec3(0.52, 0.55, 0.16), vec3(0.46, 0.31, 0.13));

float lite_water_leaf(vec2 anchored, float density, float time, out vec3 colour) {
    colour = vec3(0.0);
    ivec2 texel = ivec2(floor(anchored * 16.0));
    ivec2 cell = texel >> 3;
    if (lite_water_hash(cell + ivec2(517, 129)) > density * 0.45) {
        return 0.0;
    }
    vec2 seed = lite_water_hash2(cell + ivec2(31, 977));
    vec2 sway = vec2(sin(time * 0.23 + seed.x * 6.2831), cos(time * 0.19 + seed.y * 6.2831));
    ivec2 centre = (cell << 3) + ivec2(2) + ivec2(seed * 3.99) + ivec2(round(sway));
    ivec2 offset = texel - centre;
    if (any(greaterThan(abs(offset), ivec2(2)))) {
        return 0.0;
    }
    uint bits = lite_water_hash_bits(cell + ivec2(203, 61));
    if ((bits & 1u) != 0u) offset.x = -offset.x;
    if ((bits & 2u) != 0u) offset.y = -offset.y;
    if ((bits & 4u) != 0u) offset = offset.yx;
    int shape = LITE_LEAF_SHAPES[int((bits >> 3) & 3u)];
    int bit = (offset.y + 2) * 5 + (offset.x + 2);
    if (((shape >> bit) & 1) == 0) {
        return 0.0;
    }
    colour = LITE_LEAF_COLOURS[int((bits >> 5) & 3u)] * (0.88 + 0.12 * lite_water_hash(texel));
    return 1.0;
}

// The axis a face lies across, for pixel lock and for stepping into the fluid column beside it.
// Faces whose normal is steeper than vertical count as horizontal.
vec3 lite_water_axis_normal(vec3 normal, float vertical) {
    return abs(normal.y) > vertical ? vec3(0.0, sign(normal.y), 0.0)
        : (abs(normal.x) > abs(normal.z) ? vec3(sign(normal.x), 0.0, 0.0) : vec3(0.0, 0.0, sign(normal.z)));
}

// The water's own colour, from its texture and its tint times light. hue returns the tint at full
// light, which the depth, see-through and reflection steps reuse. Natural keeps a faint trace of the
// texture; Enhanced keeps all of its pixels, each relative to the texture's average colour (mean),
// so a resource pack's brighter or coloured water keeps its pattern without shifting Natural's
// colour.
vec3 lite_water_body(vec3 texel, vec3 mean, vec3 tintLight, vec3 light, bool realistic, bool enhanced, out vec3 hue) {
    vec3 body = texel * tintLight;
    hue = clamp(tintLight / max(light, vec3(0.03)), 0.0, 1.0);
    if (realistic) {
        if (!lite_water(LITE_WATER_BIOME_DEPTH)) {
            hue = LITE_REALISTIC_WATER;
        } else {
            hue = mix(hue, LITE_REALISTIC_WATER, 0.3);
        }
        vec3 detail = enhanced ? clamp(texel / max(mean, vec3(0.02)), 0.35, 2.2)
            : vec3(mix(1.0, dot(texel, vec3(1.0 / 3.0)) / 0.7, 0.35));
        body = hue * light * detail * 0.6;
    }
    return body;
}

// The average colour of the water sprite a texture coordinate lies in (see LiteWaterSpriteMeans).
vec3 lite_water_sprite_mean(vec2 uv) {
    if (lite_in_sprite(uv, LiteWaterStillSprite)) {
        return LiteWaterSpriteMeans[0].rgb;
    }
    if (lite_in_sprite(uv, LiteWaterFlowSprite)) {
        return LiteWaterSpriteMeans[1].rgb;
    }
    if (lite_in_sprite(uv, LiteWaterOverlaySprite)) {
        return LiteWaterSpriteMeans[2].rgb;
    }
    return lite_in_sprite(uv, LiteAlternateFluidSprites[0]) ? LiteWaterSpriteMeans[3].rgb : LiteWaterSpriteMeans[4].rgb;
}

// The water over the copied scene: what lies beneath shows through, tinted by the water so a pale
// bottom never reads as grey or white, and the water thickens and darkens with depth.
// While Voxy draws the sea beyond LiteLights' own land, its water looks like vanilla's: seen through,
// not thickened with depth. LiteLights' water hands over to that look over the last part of its own
// land, so the two meet without a dark band along the border. 1 = LiteLights' full depth look.
float lite_water_depth_look(vec3 position) {
    float landEnd = LiteFeatureValuesA.x;
    if (landEnd <= 0.0) {
        return 1.0;
    }
    return 1.0 - smoothstep(landEnd * 0.6, landEnd * 0.9, length(position.xz));
}

// How much water the view ray crosses before it reaches what lies beneath, from the copied scene's
// view depth (sceneW) and the surface's own.
float lite_water_thickness(float sceneW, vec3 position) {
    float surfaceW = 1.0 / gl_FragCoord.w;
    return clamp((sceneW - surfaceW) * length(position) / surfaceW, 0.0, 64.0);
}

// How far the water has darkened toward its deep colour, 0 to 1.
float lite_water_deepness(float thickness, bool realistic, float depthLook) {
    return (1.0 - exp(-thickness / (realistic ? 7.0 : 10.0))) * depthLook;
}

// How much of what lies beneath the water hides: alpha (the texture's own) in the shallows,
// thickening with depth.
float lite_water_depth_opacity(float thickness, float alpha, bool realistic, float depthLook) {
    // Vanilla colours never get clearer than vanilla's own water; only deeper water thickens.
    float opacity = realistic
        ? mix(0.3, 0.96, 1.0 - exp(-thickness / 3.5))
        : mix(alpha, 0.94, lite_water_deepness(thickness, realistic, depthLook));
    return mix(alpha, opacity, depthLook);
}

// pattern is the texture's own variation, which Enhanced keeps in deep water too (1 otherwise).
vec3 lite_water_over_scene(vec4 scene, vec3 body, vec3 hue, vec3 light, vec3 pattern, bool realistic, float alpha, vec3 position) {
    float opacity = alpha;
    vec3 throughWater = vec3(1.0);
    float depthLook = lite_water_depth_look(position);
    if (lite_water(LITE_WATER_BIOME_DEPTH) && depthLook > 0.0) {
        float thickness = lite_water_thickness(scene.a, position);
        float deep = lite_water_deepness(thickness, realistic, depthLook);
        if (realistic) {
            // Deep water loses red first, then green: deeper blues and greens.
            vec3 deepColour = (hue * vec3(0.16, 0.30, 0.45) + vec3(0.0, 0.015, 0.03)) * light * pattern;
            body = mix(body, deepColour, deep);
        } else {
            body *= mix(1.0, 0.55, deep);
        }
        opacity = lite_water_depth_opacity(thickness, alpha, realistic, depthLook);
        vec3 tintOnly = hue / max(max(hue.r, hue.g), max(hue.b, 0.05));
        throughWater = mix(vec3(1.0), tintOnly, min(1.0, 0.2 + thickness / 8.0) * (realistic ? 0.6 : 0.25));
        throughWater = mix(vec3(1.0), throughWater, depthLook);
    }
    return mix(scene.rgb * throughWater, body, opacity);
}

// The alpha a water top face blends with under Improved Transparency, in every OIT stage.
//
// Improved Transparency blends a translucent surface over what lies behind it with the alpha its
// alpha-only stages saw, and the colour can't make up for that by going below zero: the composite
// writes into the 8-bit main target, which clamps it. With the texture's own alpha, deep water
// that should look darker than the bottom showing through would need such a colour, so its channels
// were cut off at zero and the bottom showed through too strongly, most in the red of a sand or
// dirt bottom: a purple tint over deep water. The classic path raises its alpha where needed
// (lite_water_blend_over_scene); here every stage raises it to the depth's own opacity instead,
// which the alpha-only stages can work out from the scene copy alone. The colour stage then solves
// for a colour in range, and the transmittance matches it.
float lite_water_coverage(float alpha, vec4 scene, vec3 position, bool realistic) {
    float depthLook = lite_water_depth_look(position);
    if (!lite_water(LITE_WATER_BIOME_DEPTH) || depthLook <= 0.0) {
        return alpha;
    }
    float opacity = lite_water_depth_opacity(lite_water_thickness(scene.a, position), alpha, realistic, depthLook);
    return max(alpha, opacity);
}

// Sky and screen-space reflections on a top face, added over what is seen. swellSlope tilts the
// surface by the ocean waves' swell (zero without them).
vec3 lite_water_reflect(vec3 seen, vec3 surfacePosition, vec2 lightCoord, vec4 shade, vec3 hue, bool realistic, float time, vec2 swellSlope) {
    vec3 waveNormal = lite_water_wave_normal(lite_water_anchored(surfacePosition), time, length(surfacePosition));
    if (swellSlope != vec2(0.0)) {
        waveNormal = normalize(waveNormal + vec3(-swellSlope.x, 0.0, -swellSlope.y));
    }
    vec3 view = normalize(surfacePosition - lite_view_origin());
    vec3 direction = reflect(view, waveNormal);
    direction.y = max(direction.y, 0.02);
    direction = normalize(direction);
    float facing = clamp(dot(-view, waveNormal), 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - facing, 5.0);
    // Reflections add to the water's colour rather than replace it: with Vanilla colours they
    // stay a light touch at grazing angles, Realistic lets them reach further.
    float reflectance = fresnel * LiteWaterParams.x * (realistic ? 0.8 : 0.5);
    reflectance = min(reflectance, realistic ? 0.7 : 0.4);
    vec3 reflection = lite_water_shaded_sky(direction, shade) * lite_water_sky_visibility(lightCoord);
    // Looking down into the water the reflection is too faint to see: don't trace it.
    if (lite_scene_copy_valid() && reflectance > 0.03) {
        // A fixed step offset: a random one per texel or pixel turns distant reflections to noise.
        float jitter = 0.5;
        int steps = lite_loop_count(LiteWaterParams.y, LITE_WATER_TRACE_MAX_STEPS);
        vec4 hit = lite_water_trace(surfacePosition, direction, jitter, steps, steps > 16 ? 5 : 3);
        reflection = mix(reflection, hit.rgb, hit.a);
    }
    if (!realistic) {
        // Seen through vanilla's tinted water, reflections keep some of the water's own colour.
        reflection *= mix(vec3(1.0), hue / max(max(hue.r, hue.g), max(hue.b, 0.05)), 0.35);
    }
    return mix(seen, reflection, reflectance);
}

// --- Wakes (Wakes on the Water page; idea by Goby56, Wakes) ---

// How far a wake's slope tilts the reflections and the sun's glare in them.
const float LITE_WAKE_TILT = 0.6;

// A wake's light on the water: its crests and the slopes facing the sun or moon catch the light,
// troughs and the far slopes fall into shade, in four bands on the texel grid; and foam, in whole
// texels, where the field has some, broken into lace by a fixed noise so it frays as it thins, with
// the same faint grain as the waves' and edge foam (waves097). Mirrored in WakeLook (tests), less the
// grain.
vec3 lite_wake_colour(vec3 seen, LiteWake wake, vec3 light, vec3 surfacePosition, float texelsPerBlock) {
    vec2 anchored = lite_water_anchored(surfacePosition);
    float dither = lite_wave_dither(ivec2(floor(anchored * texelsPerBlock)));
    float lit = clamp(0.5 + wake.height * 2.0 - dot(wake.slope, LiteShadowLightDirection.xz) * 0.9, 0.0, 1.0);
    float bands = floor(lit * 4.0 + dither) / 4.0;
    seen *= 1.0 + (bands - 0.5) * 0.5 * wake.reach;
    if (LiteWakeParams.x > 0.5 && wake.foam > 0.0) {
        float lace = lite_water_value_noise(anchored * 3.0);
        float amount = wake.foam * 1.4 - 0.25 + (lace - 0.5) * 0.6;
        float fine = lite_wave_fine(lite_wave_footprint(surfacePosition));
        vec3 foamColour = LITE_FOAM_COLOUR * light * lite_wave_grain(anchored, fine);
        seen = mix(seen, foamColour, lite_wave_foam_cover(amount, dither, fine) * 0.9 * wake.reach);
    }
    return seen;
}

// --- Drifting foam (Foam motion: Drifting) ---

// How far foam travels in a second on water whose flow is 1 (vanilla's flowing water), in blocks.
const float LITE_FOAM_DRIFT_SPEED = 0.6;
// Each of the two copies of a drifting pattern starts over once in this many seconds.
const float LITE_FOAM_DRIFT_CYCLE = 4.0;

// A foam noise pattern carried downstream. p is the pattern's coordinate and ownMotion the way the
// stationary pattern moves by itself (both in pattern cells, scale of them to a block); flow is the
// water's flow in blocks (lite_fluid_flow). On flowing water the pattern's own motion gives way to
// the current, down to keepOnFlow of it, so the travel downstream is what the eye follows. Two copies
// slide with the flow, each starting over where it began once a cycle while the other is at its
// strongest, so the pattern travels without stretching where the flow bends or slows. Blending two
// copies flattens the noise, which the last step undoes as far as the water flows. With no flow both
// copies are the stationary pattern itself, so still water keeps its foam as it is.
float lite_water_drifting_noise(vec2 p, vec2 ownMotion, float keepOnFlow, float scale, vec2 flow, float time) {
    float flowing = min(1.0, length(flow) * 8.0);
    vec2 start = p + ownMotion * mix(1.0, keepOnFlow, flowing);
    float phase = fract(time / LITE_FOAM_DRIFT_CYCLE);
    float otherPhase = fract(phase + 0.5);
    vec2 travel = flow * (LITE_FOAM_DRIFT_SPEED * LITE_FOAM_DRIFT_CYCLE * scale);
    float first = lite_water_value_noise(start - travel * phase);
    float second = lite_water_value_noise(start - travel * otherPhase);
    float weight = 1.0 - abs(2.0 * phase - 1.0);
    float blended = mix(second, first, weight);
    float flattening = inversesqrt(weight * weight + (1.0 - weight) * (1.0 - weight));
    return clamp(0.5 + (blended - 0.5) * mix(1.0, flattening, flowing), 0.0, 1.0);
}

// Foam where falling water lands, carried downstream: the landing strength a little way upstream,
// fading with the distance it has come. The trail is as long as the foam travels in about four
// seconds, so it reaches further on faster water; on still water there is none.
float lite_water_landing_trail(vec3 surfacePosition, vec2 flow) {
    float landing = lite_fluid_landing(surfacePosition);
    if (dot(flow, flow) < 0.0025) {
        return landing;
    }
    vec3 stride = vec3(flow.x, 0.0, flow.y) * (LITE_FOAM_DRIFT_SPEED * 1.4);
    landing = max(landing, 0.75 * lite_fluid_landing(surfacePosition - stride));
    landing = max(landing, 0.5 * lite_fluid_landing(surfacePosition - stride * 2.0));
    return max(landing, 0.25 * lite_fluid_landing(surfacePosition - stride * 3.0));
}

// The shore foam and the landing foam of lite_water_surface_details, with their patterns carried by
// the flow. The foam's place is unchanged: shore foam still only lines the real waterline.
vec3 lite_water_drifting_foam(vec3 seen, LiteFluidColumn column, vec3 surfacePosition, vec2 anchored, vec3 light, float time) {
    vec2 flow = lite_fluid_flow(surfacePosition);
    if (lite_water(LITE_WATER_SHORE_FOAM) && column.shoreMask != 0) {
        vec2 inColumn = fract(lite_block_local(surfacePosition).xz);
        float distanceToShore = lite_fluid_shore_distance(column.shoreMask, inColumn);
        float noise = lite_water_drifting_noise(anchored * 4.0, vec2(time * 0.35, -time * 0.2), 0.0, 4.0, flow, time);
        float reach = 0.09 + 0.15 * noise + 0.04 * sin(time * 1.6 + noise * 6.2831);
        float foam = 1.0 - smoothstep(reach - 0.025, reach + 0.025, distanceToShore);
        float wash = 0.5 + 0.5 * sin(time * 0.9 + noise * 2.0);
        float outer = reach + 0.12 + 0.1 * wash;
        foam = max(foam, (1.0 - smoothstep(0.0, 0.03, abs(distanceToShore - outer))) * 0.5 * (1.0 - wash));
        seen = mix(seen, lite_foam_grained(LITE_FOAM_COLOUR, surfacePosition) * light, foam * 0.85 * column.inMap);
    }
    if (lite_water(LITE_WATER_FALLING_WATER)) {
        float landing = lite_water_landing_trail(surfacePosition, flow);
        if (landing > 0.0) {
            // The water still churns where it lands, a little, while the current carries the foam off.
            float churn = lite_water_drifting_noise(anchored * 5.0, vec2(time * 1.3, time * 0.9), 0.3, 5.0, flow, time);
            churn = max(churn, lite_water_drifting_noise(anchored * 3.0, -vec2(time * 0.8, time * 1.4), 0.3, 3.0, flow, time));
            float foam = smoothstep(0.55, 0.75, churn + landing * 0.75 - 0.25) * min(1.0, landing * 1.6);
            seen = mix(seen, LITE_FOAM_COLOUR * light, foam * 0.9 * column.inMap);
        }
    }
    return seen;
}

// Shore foam, the foam where falling water lands, and floating leaves, on the top of the water.
// position is the surface's own position, surfacePosition the pixel-locked one. Stationary foam is
// drawn here as it always was; Drifting hands the two foams to lite_water_drifting_foam.
vec3 lite_water_surface_details(vec3 seen, LiteFluidColumn column, vec3 position, vec3 surfacePosition, vec3 light, float time) {
    vec2 anchored = lite_water_anchored(surfacePosition);
    if (lite_water(LITE_WATER_DRIFTING_FOAM)) {
        seen = lite_water_drifting_foam(seen, column, surfacePosition, anchored, light, time);
    } else {
        if (lite_water(LITE_WATER_SHORE_FOAM) && column.shoreMask != 0) {
            vec2 inColumn = fract(lite_block_local(surfacePosition).xz);
            float distanceToShore = lite_fluid_shore_distance(column.shoreMask, inColumn);
            float noise = lite_water_value_noise(anchored * 4.0 + vec2(time * 0.35, -time * 0.2));
            float reach = 0.09 + 0.15 * noise + 0.04 * sin(time * 1.6 + noise * 6.2831);
            float foam = 1.0 - smoothstep(reach - 0.025, reach + 0.025, distanceToShore);
            // A thinner line farther out that washes in and out.
            float wash = 0.5 + 0.5 * sin(time * 0.9 + noise * 2.0);
            float outer = reach + 0.12 + 0.1 * wash;
            foam = max(foam, (1.0 - smoothstep(0.0, 0.03, abs(distanceToShore - outer))) * 0.5 * (1.0 - wash));
            seen = mix(seen, lite_foam_grained(LITE_FOAM_COLOUR, surfacePosition) * light, foam * 0.85 * column.inMap);
        }
        if (lite_water(LITE_WATER_FALLING_WATER)) {
            float landing = lite_fluid_landing(surfacePosition);
            if (landing > 0.0) {
                float churn = lite_water_value_noise(anchored * 5.0 + vec2(time * 1.3, time * 0.9));
                churn = max(churn, lite_water_value_noise(anchored * 3.0 - vec2(time * 0.8, time * 1.4)));
                float foam = smoothstep(0.55, 0.75, churn + landing * 0.75 - 0.25) * min(1.0, landing * 1.6);
                seen = mix(seen, LITE_FOAM_COLOUR * light, foam * 0.9 * column.inMap);
            }
        }
    }
    if (lite_water(LITE_WATER_FLOATING_LEAVES) && column.leaves > 0.0) {
        vec3 leafColour;
        float leaf = lite_water_leaf(lite_water_anchored(position), column.leaves, time, leafColour);
        seen = mix(seen, leafColour * light, leaf * column.inMap);
    }
    return seen;
}

// Falling water runs white, more so the farther it has fallen: dashed streaks down the side faces.
vec3 lite_water_falling_white(vec3 seen, LiteFluidColumn column, vec3 position, vec3 surfacePosition, vec3 axisNormal, vec3 light,
                              float time) {
    float fallen = column.surfaceY - position.y;
    if (fallen > 1.0) {
        vec3 local = lite_block_local(surfacePosition);
        float along = abs(axisNormal.x) > 0.5 ? local.z : local.x;
        int stripe = int(floor(along * 16.0));
        int dash = int(floor((local.y * 16.0 + time * 26.0) / 5.0));
        float streak = step(0.4, lite_water_hash(ivec2(stripe, dash)));
        float white = smoothstep(1.0, 7.0, fallen) * (0.45 + 0.55 * streak);
        seen = mix(seen, LITE_FOAM_COLOUR * light, white * 0.8);
    }
    return seen;
}

// The hardware blends this fragment over the real framebuffer with its alpha. Solve for the colour
// that gives "seen" over the copied scene. Outside Improved Transparency the alpha can be raised
// where needed to keep the colour in range; inside it the alpha must stay as the alpha-only stages
// saw it (lite_water_coverage, already in color.a).
void lite_water_blend_over_scene(inout vec4 color, vec3 seen, vec3 behind) {
    float alpha = color.a;
#ifdef OIT_ACCUMULATE
    color.rgb = max((seen - behind * (1.0 - alpha)) / alpha, vec3(0.0));
#else
    vec3 brighter = (seen - behind) / max(vec3(1.0) - behind, vec3(1.0e-3));
    vec3 darker = vec3(1.0) - seen / max(behind, vec3(1.0e-3));
    float needed = max(max(max(brighter.r, brighter.g), brighter.b), max(max(darker.r, darker.g), darker.b));
    float outputAlpha = clamp(max(alpha, needed), alpha, 1.0);
    color = vec4(clamp((seen - behind * (1.0 - outputAlpha)) / outputAlpha, 0.0, 1.0), outputAlpha);
#endif
}

// A water top face seen from above, with the opaque scene copied: the water is drawn over that copy.
bool lite_water_composites(LiteSurface surface) {
    return surface.normal.y > 0.3 && !lite_feature(LITE_FEATURE_CAMERA_UNDERWATER) && lite_scene_copy_valid();
}

void lite_shade_water(inout vec4 color, LiteSurface surface, vec3 dpdx, vec3 dpdy, vec2 duvdx, vec2 duvdy) {
    float time = LiteTime.y;
    bool topFace = surface.normal.y > 0.3;
    bool cameraUnderwater = lite_feature(LITE_FEATURE_CAMERA_UNDERWATER);
    // Enhanced is Natural's shading over the texture's own pixels.
    bool enhanced = lite_water(LITE_WATER_ENHANCED);
    bool realistic = lite_water(LITE_WATER_REALISTIC) || enhanced;

    // Split the shaded colour back into texture and tint-times-light, so the texture can be swapped
    // and the tint reused. The atlas holds the sprite as drawn: its current animation frame, turned
    // with the flow, whichever pack or mod it comes from.
    vec4 atlasTexel = textureGrad(LiteWaterAtlas, surface.uv, duvdx, duvdy);
    vec3 tintLight = color.rgb / max(atlasTexel.rgb, vec3(0.04));
    vec3 light = vec3(lite_water_light_level(tintLight));
    vec3 texel = atlasTexel.rgb;
    vec3 mean = lite_water_sprite_mean(surface.uv);
    float texelsPerBlock = lite_water_texels_per_block(duvdx, duvdy, dpdx, dpdy, vec2(textureSize(LiteWaterAtlas, 0)));
    // The bundled large textures stand in for vanilla's water only: with Enhanced, a resource
    // pack's or another mod's water keeps its own pixels.
    if (lite_water(LITE_WATER_LARGE_WATER) && (!enhanced || lite_water(LITE_WATER_VANILLA_SPRITES))) {
        vec4 large;
        if (lite_large_fluid(surface.position, surface.normal, surface.uv, false, dpdx, dpdy, duvdx, duvdy, large)) {
            texel = large.rgb;
            mean = lite_in_sprite(surface.uv, LiteWaterStillSprite) ? LiteWaterSpriteMeans[5].rgb : LiteWaterSpriteMeans[6].rgb;
            // Its frames hold as many texels per block as the strip's still frame over its tile.
            texelsPerBlock = LiteFluidStripLayout.x * float(textureSize(LiteFluidData, 0).x) / LiteWaterParams.z;
        }
    }

    // The pixel-locked position for waves, the reflection ripple and the foam edge, on the grid of
    // the texture's own texels.
    vec3 axisNormal = lite_water_axis_normal(surface.normal, 0.3);
    vec3 surfacePosition = surface.position;
    if (lite_locked(LITE_LOCK_WATER_SURFACE)) {
        surfacePosition = lite_water_lock(surface.position, axisNormal, texelsPerBlock);
    }

    vec3 hue;
    vec3 body = lite_water_body(texel, mean, tintLight, light, realistic, enhanced, hue);

    bool composite = lite_water_composites(surface);
    vec3 behind = vec3(0.0);
    vec3 seen = body;
    float coverage = color.a;
    if (composite) {
        vec4 scene = texelFetch(LiteScene, ivec2(gl_FragCoord.xy), 0);
        behind = scene.rgb;
        vec3 pattern = enhanced ? clamp(texel / max(mean, vec3(0.02)), 0.35, 2.2) : vec3(1.0);
        seen = lite_water_over_scene(scene, body, hue, light, pattern, realistic, color.a, surface.position);
        coverage = lite_water_coverage(color.a, scene, surface.position, realistic);
    }

    LiteFluidColumn column = lite_fluid_column(lite_water_world_column(surfacePosition - axisNormal * 0.02));
    bool atSurface = column.present && !column.lava && abs(column.surfaceY - surface.position.y) < 0.3;

    LiteSwell swell = lite_no_swell();
    if (topFace && !cameraUnderwater && lite_water(LITE_WATER_OCEAN_WAVES)) {
        swell = lite_ocean_swell(surfacePosition);
    }
    // The wake field is flat (one height per column), so only the water's own top reads it.
    LiteWake wake = lite_no_wake();
    if (topFace && !cameraUnderwater && atSurface && lite_water(LITE_WATER_WAKES)) {
        wake = lite_wake(surfacePosition);
    }

    if (topFace && !cameraUnderwater && lite_water(LITE_WATER_REFLECTIONS)) {
        vec4 shade = lite_mirror_shade(surface.shadow, surface.facing);
        vec2 tilt = swell.slope + wake.slope * (LITE_WAKE_TILT * wake.reach);
        seen = lite_water_reflect(seen, surfacePosition, surface.lightCoord, shade, hue, realistic, time, tilt);
    }
    if (wake.reach > 0.0) {
        seen = lite_wake_colour(seen, wake, light, surfacePosition, texelsPerBlock);
    }

    if (topFace && atSurface) {
        seen = lite_water_surface_details(seen, column, surface.position, surfacePosition, light, time);
    }
    // After the foam, so a passing crest's light lies over it (waves097).
    if (swell.sea > 0.0) {
        seen = lite_ocean_swell_colour(seen, swell, light, surfacePosition);
    }
    if (!topFace && lite_water(LITE_WATER_FALLING_WATER) && column.present && !column.lava) {
        seen = lite_water_falling_white(seen, column, surface.position, surfacePosition, axisNormal, light, time);
    }

    if (composite) {
#ifdef OIT_ACCUMULATE
        color.a = coverage;
#endif
        lite_water_blend_over_scene(color, seen, behind);
    } else {
        color.rgb = seen;
    }
}

void lite_shade_lava(inout vec4 color, LiteSurface surface, vec3 dpdx, vec3 dpdy, vec2 duvdx, vec2 duvdy) {
    float time = LiteTime.y;
    bool topFace = surface.normal.y > 0.3;
    vec3 lava = color.rgb;
    if (lite_water(LITE_WATER_LARGE_LAVA)) {
        vec4 large;
        if (lite_large_fluid(surface.position, surface.normal, surface.uv, true, dpdx, dpdy, duvdx, duvdy, large)) {
            vec4 atlasTexel = textureGrad(LiteWaterAtlas, surface.uv, duvdx, duvdy);
            lava = large.rgb * color.rgb / max(atlasTexel.rgb, vec3(0.04));
        }
    }

    if (topFace && lite_water(LITE_WATER_LAVA_CRUST)) {
        vec3 position = lite_locked(LITE_LOCK_WATER_SURFACE) ? lite_water_lock_horizontal(surface.position) : surface.position;
        LiteFluidColumn column = lite_fluid_column(lite_water_world_column(position));
        if (column.present && column.lava && abs(column.surfaceY - surface.position.y) < 0.3) {
            vec2 anchored = lite_water_anchored(position);
            float edge = column.shoreMask != 0 ? lite_fluid_shore_distance(column.shoreMask, fract(lite_block_local(position).xz)) : 2.0;
            float noise = lite_water_value_noise(anchored * 1.5 + vec2(time * 0.03, -time * 0.02));
            float crust = 1.0 - smoothstep(0.38, 0.5, edge + (noise - 0.5) * 0.7);
            // Small rafts of cooled crust drifting on open lava.
            float rafts = lite_water_value_noise(anchored * 1.1 - vec2(time * 0.015, time * 0.01));
            crust = max(crust, smoothstep(0.86, 0.89, rafts));
            if (crust > 0.0) {
                float cracks = lite_water_cell_edges(anchored * 2.5, time * 0.05);
                float crack = 1.0 - smoothstep(0.04, 0.09, cracks);
                vec3 rock = vec3(0.16, 0.11, 0.08) * (0.8 + 0.4 * lite_water_hash(ivec2(floor(anchored * 16.0))));
                // The crust still glows a little where it is thin, near its edge.
                vec3 thinGlow = lava * 0.35 * (1.0 - smoothstep(0.0, 0.5, crust));
                vec3 crustColour = mix(rock + thinGlow, lava * 1.15, crack);
                lava = mix(lava, crustColour, smoothstep(0.0, 0.25, crust));
            }
        }
    }
    color.rgb = lava;
}

void lite_shade_ice(inout vec4 color, LiteSurface surface) {
    vec3 view = normalize(surface.position - lite_view_origin());
    float facing = clamp(dot(-view, surface.normal), 0.0, 1.0);
    float fresnel = 0.04 + 0.96 * pow(1.0 - facing, 5.0);
    vec3 direction = reflect(view, surface.normal);
    direction.y = max(direction.y, 0.02);
    direction = normalize(direction);
    vec4 shade = lite_mirror_shade(surface.shadow, surface.facing);
    vec3 reflection = lite_water_shaded_sky(direction, shade) * lite_water_sky_visibility(surface.lightCoord);
    if (surface.normal.y > 0.5 && lite_scene_copy_valid() && !lite_feature(LITE_FEATURE_CAMERA_UNDERWATER)) {
        float jitter = lite_water_hash(ivec2(gl_FragCoord.xy));
        vec4 hit = lite_water_trace(surface.position, direction, jitter, max(8, lite_loop_count(LiteWaterParams.y, LITE_WATER_TRACE_MAX_STEPS) / 2), 2);
        reflection = mix(reflection, hit.rgb, hit.a);
    }
    color.rgb = mix(color.rgb, reflection, clamp(fresnel * LiteWaterParams.x * 0.8, 0.0, 1.0));
}

// Rippling light on blocks under water: two drifting layers of cell edges, offset toward the
// light's direction by the depth, strongest in shallow, sunlit water.
void lite_apply_caustics(inout vec4 color, LiteSurface surface) {
    if (surface.lightCoord.y < 24.0) {
        return;
    }
    vec3 axisNormal = lite_water_axis_normal(surface.normal, 0.5);
    vec3 position = lite_locked(LITE_LOCK_CAUSTICS) ? lite_pixel_lock_face(surface.position, axisNormal) : surface.position;
    LiteFluidColumn column = lite_fluid_column(lite_water_world_column(position + axisNormal * 0.02));
    if (!column.present || column.lava) {
        return;
    }
    float depth = column.surfaceY - position.y;
    if (depth < 0.05 || depth > 32.0) {
        return;
    }
    bool moon = LiteShadowLightDirection.w > 0.5;
    float light = (moon ? 0.2 * (1.0 - LiteSunColor.a) : LiteSunColor.a) * (1.0 - 0.75 * LiteWeather.x);
    if (light <= 0.01) {
        return;
    }
    vec3 towardLight = LiteShadowLightDirection.xyz;
    towardLight.y = max(towardLight.y, 0.25);
    vec2 p = lite_water_anchored(position) + towardLight.xz / towardLight.y * depth;
    float time = LiteTime.y;
    float first = 1.0 - smoothstep(0.02, 0.26, lite_water_cell_edges(p, time * 0.9));
    float second = 1.0 - smoothstep(0.02, 0.26, lite_water_cell_edges(p * 1.5 + 0.37, time * 1.1));
    // Where the two layers' bright lines cross, light gathers most; elsewhere they glow faintly.
    float pattern = first * second + 0.25 * (first + second);
    // Light coming down through the surface falls mostly on upward faces; walls get a weaker share.
    float facingUp = mix(0.35, 1.0, max(surface.normal.y, 0.0));
    float strength = pattern * light * surface.shadow * exp(-depth * 0.15) * smoothstep(0.05, 0.6, depth) * facingUp * column.inMap
        * lite_water_sky_visibility(surface.lightCoord);
    color.rgb += color.rgb * LiteSunColor.rgb * strength * 0.4;
}

// The Improved Transparency alpha-only stages' side of lite_water_coverage: the same alpha the
// colour stage blends a water top face with, so the transmittance matches it.
void lite_water_oit_alpha(inout vec4 color, LiteSurface surface) {
    if (LiteWaterFlags.x == 0 || !lite_is_water_sprite(surface.uv) || !lite_water_composites(surface)) {
        return;
    }
    bool realistic = lite_water(LITE_WATER_REALISTIC) || lite_water(LITE_WATER_ENHANCED);
    vec4 scene = texelFetch(LiteScene, ivec2(gl_FragCoord.xy), 0);
    color.a = lite_water_coverage(color.a, scene, surface.position, realistic);
}

void lite_water_surface(inout vec4 color, LiteSurface surface) {
#if defined(LITE_TERRAIN_WATER) || defined(LITE_TERRAIN_CAUSTICS)
    if (LiteWaterFlags.x == 0) {
        return;
    }
    // Derivatives first, while every invocation is still running the same code.
    vec3 dpdx = dFdx(surface.position);
    vec3 dpdy = dFdy(surface.position);
    vec2 duvdx = dFdx(surface.uv);
    vec2 duvdy = dFdy(surface.uv);

    if (lite_is_water_sprite(surface.uv)) {
#ifdef LITE_TERRAIN_WATER
        lite_shade_water(color, surface, dpdx, dpdy, duvdx, duvdy);
#endif
    } else if (lite_is_lava_sprite(surface.uv)) {
#ifdef LITE_TERRAIN_WATER
        lite_shade_lava(color, surface, dpdx, dpdy, duvdx, duvdy);
#endif
    } else {
#ifdef LITE_TERRAIN_WATER
        bool ice = surface.material == LITE_MATERIAL_ICE || lite_is_ice_sprite(surface.uv);
        if (ice && lite_water(LITE_WATER_ICE_REFLECTIONS)) {
            lite_shade_ice(color, surface);
        }
#endif
#ifdef LITE_TERRAIN_CAUSTICS
        if (lite_water(LITE_WATER_CAUSTICS)) {
            lite_apply_caustics(color, surface);
        }
        if (lite_water(LITE_WATER_OCEAN_WAVES)) {
            lite_ocean_swash(color, surface);
        }
#endif
    }
#endif
}

#endif
