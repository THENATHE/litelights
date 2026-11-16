#ifndef LITELIGHTS_FARTERRAIN_FAR_WATER_GLSL
#define LITELIGHTS_FARTERRAIN_FAR_WATER_GLSL

// Far water: which far pixels are water, where their surface and the ground under them are, and
// (with LITE_FAR_WATER_DETAILS) the Experimental extras on it: caustics on the ground, depth
// colour and edge foam. Included by far_terrain.fsh after it declares its samplers.
//
// Water is found by what the far mod drew in its translucent pass (FarWaterCapture):
//   - Voxy: FarWaterA and FarWaterB are Voxy's colour just before and just after that pass.
//     Where they differ, translucent geometry was drawn over the ground. Voxy draws water without
//     depth, so the depth here is the ground and the surface height comes from the shore around
//     the water (lite_far_shore), or the sea surface when no shore is in sight.
//   - Distant Horizons: FarWaterA is its depth just before that pass, the ground; its depth now
//     is the water's surface. Where the two differ there is water, and that is its thickness.
// Without a capture (FarWaterSwitches.x is 0) the sea is found by its height as before
// (lite_far_sea_surface). Distant stained glass and ice count as water too.

#include <litelights:farterrain/far_light.glsl>

struct LiteFarWater {
    bool found;
    vec3 surface;   // where the view ray enters the water, camera-relative
    vec3 ground;    // where it reaches the ground under it
    bool measured;  // the ground is known (not just the surface)
};

// One screen pixel's width at this point, in blocks: how coarse the far mod's detail is here.
float lite_far_footprint(vec2 uv, float depth, vec3 position) {
    vec2 texel = 1.0 / vec2(textureSize(SceneDepth, 0));
    return max(length(lite_far_position(uv + vec2(0.0, texel.y), depth) - position), 1.0e-3);
}

bool lite_far_captured() {
    return FarWaterSwitches.x > 0.5;
}

#ifndef LITE_FAR_SOURCE_DH
// Voxy's translucent pass drew over this pixel.
bool lite_far_translucent_at(ivec2 pixel) {
    vec3 before = texelFetch(FarWaterA, pixel, 0).rgb;
    vec3 after = texelFetch(FarWaterB, pixel, 0).rgb;
    vec3 change = abs(after - before);
    return max(change.r, max(change.g, change.b)) > 1.5 / 255.0;
}

// A pixel of the far mod's own terrain (not LiteLights' land, not the sky).
bool lite_far_terrain_pixel(ivec2 pixel, out vec3 position) {
    position = vec3(0.0);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    if (!lite_far_has_depth(depth)) {
        return false;
    }
    float alpha = texelFetch(SceneColor, pixel, 0).a;
    if (alpha <= LITE_EMISSIVE_ALPHA_LIMIT || abs(alpha - LITE_NEAR_ALPHA) <= 0.5 / 255.0) {
        return false;
    }
    position = lite_far_position((vec2(pixel) + 0.5) / vec2(textureSize(SceneDepth, 0)), depth);
    return true;
}
#else
// Distant Horizons: the ground under this pixel lies further than what is drawn there now.
bool lite_far_translucent_at(ivec2 pixel) {
    float now = texelFetch(SceneDepth, pixel, 0).r;
    float ground = texelFetch(FarWaterA, pixel, 0).r;
    if (!lite_far_has_depth(now)) {
        return false;
    }
    vec2 uv = (vec2(pixel) + 0.5) / vec2(textureSize(SceneDepth, 0));
    float surfaceDistance = length(lite_far_position(uv, now));
    float groundDistance = lite_far_has_depth(ground) ? length(lite_far_position(uv, ground)) : surfaceDistance + 64.0;
    return groundDistance - surfaceDistance > 0.1;
}

bool lite_far_terrain_pixel(ivec2 pixel, out vec3 position) {
    position = vec3(0.0);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    if (!lite_far_has_depth(depth)) {
        return false;
    }
    position = lite_far_position((vec2(pixel) + 0.5) / vec2(textureSize(SceneDepth, 0)), depth);
    return true;
}
#endif

// A pixel next to one the translucent pass drew over still counts as water: inside the water a
// pixel can be left as it was here and there, and the shore is then found one pixel further out,
// on land that is certainly dry.
bool lite_far_water_area(ivec2 pixel) {
    return lite_far_translucent_at(pixel)
        || lite_far_translucent_at(pixel + ivec2(1, 0)) || lite_far_translucent_at(pixel - ivec2(1, 0))
        || lite_far_translucent_at(pixel + ivec2(0, 1)) || lite_far_translucent_at(pixel - ivec2(0, 1));
}

// The shore around a water pixel: in eight screen directions, the first pixel of far terrain that
// isn't water, at or above the ground under the water (the water's surface can't be lower than its
// own floor, which rules out land seen far below past a drop). Returns in shoreHeight the middle
// one of the heights found (a tree or a hill in one direction, or a stray low pixel in another,
// can't pull it far), and in reach the screen distance in pixels to the nearest shore. Land nearer
// the camera than nearest is in front of the water, not beside it (a silhouette), and hides the
// shore that way. shoreHeight is 1e9 when no shore was found. With levelTolerance at 0 or more,
// reach only counts land within that height of levelHeight: the waterline itself, not a bridge, a
// dock or an overhang beside the water on screen.
void lite_far_shore(ivec2 pixel, float groundHeight, float nearest, int farSteps, float levelHeight, float levelTolerance,
                    out float shoreHeight, out float reach) {
    float heights[8];
    int found = 0;
    const ivec2 DIRECTIONS[8] = ivec2[8](ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1),
                                         ivec2(1, 1), ivec2(-1, 1), ivec2(1, -1), ivec2(-1, -1));
    const int STEPS[8] = int[8](1, 2, 3, 6, 12, 24, 48, 96);
    ivec2 last = textureSize(SceneDepth, 0) - 1;
    float landEnd = LiteFeatureValuesA.y;
    reach = 1.0e9;
    for (int d = 0; d < 8; d++) {
        int lastWater = 0;
        for (int s = 0; s < farSteps; s++) {
            ivec2 at = pixel + DIRECTIONS[d] * STEPS[s];
            if (any(lessThan(at, ivec2(0))) || any(greaterThan(at, last))) {
                break;
            }
            if (lite_far_water_area(at)) {
                lastWater = STEPS[s];
                continue;
            }
            // The steps grow, so they can jump over a thin shore onto land beyond it: narrow down
            // to the first pixel past the water's edge.
            int dry = STEPS[s];
            for (int i = 0; i < 6 && dry - lastWater > 1; i++) {
                int middle = (lastWater + dry) / 2;
                if (lite_far_water_area(pixel + DIRECTIONS[d] * middle)) {
                    lastWater = middle;
                } else {
                    dry = middle;
                }
            }
            at = pixel + DIRECTIONS[d] * dry;
            vec3 shore;
            bool farTerrain = lite_far_terrain_pixel(at, shore);
            // Right past LiteLights' own land (its sections reach a little beyond the render
            // distance, and Voxy fills gaps there) nothing is confirmed far shore.
            bool pastLandEnd = landEnd <= 0.0 || length(shore.xz) > landEnd + 24.0;
            if (farTerrain && pastLandEnd && shore.y >= groundHeight - 0.5 && length(shore) >= nearest) {
                heights[found++] = shore.y;
                if (levelTolerance < 0.0 || abs(shore.y - levelHeight) <= levelTolerance) {
                    reach = min(reach, float(dry) * length(vec2(DIRECTIONS[d])));
                }
            }
            // LiteLights' own land, the sky or the screen's edge: no shore this way.
            break;
        }
    }
    shoreHeight = 1.0e9;
    if (found == 0) {
        return;
    }
    // A small insertion sort, then the middle value (the lower middle for an even count).
    for (int i = 1; i < found; i++) {
        float value = heights[i];
        int j = i - 1;
        while (j >= 0 && heights[j] > value) {
            heights[j + 1] = heights[j];
            j--;
        }
        heights[j + 1] = value;
    }
    shoreHeight = heights[(found - 1) / 2];
}

// Whether a far pixel is water, and where its surface and the ground under it are.
// wantsThickness asks Voxy's water for its true surface height (the shore search), which only
// the depth colour and caustics need.
LiteFarWater lite_far_find_water(ivec2 pixel, vec2 uv, float depth, vec3 position, vec3 normal, bool wantsThickness) {
    LiteFarWater water = LiteFarWater(false, position, position, false);
    float seaSurface = FarShadowRange.z;
    if (!lite_far_captured()) {
        vec3 surface;
        water.found = lite_far_sea_surface(position, normal, seaSurface, surface);
        water.surface = surface;
        #ifndef LITE_FAR_SOURCE_DH
        water.measured = water.found;
        #endif
        return water;
    }
    if (!lite_far_water_area(pixel)) {
        return water;
    }
    water.found = true;
    #ifdef LITE_FAR_SOURCE_DH
    float ground = texelFetch(FarWaterA, pixel, 0).r;
    water.measured = lite_far_has_depth(ground);
    water.ground = water.measured ? lite_far_position(uv, ground) : position * (1.0 + 64.0 / max(length(position), 1.0));
    #else
    // The depth is the ground: the surface is where the view ray crossed the water's height.
    float surfaceHeight = position.y < seaSurface - 0.3 ? seaSurface : position.y;
    if (wantsThickness) {
        float shoreHeight;
        float reach;
        lite_far_shore(pixel, position.y, 0.0, 8, 0.0, -1.0, shoreHeight, reach);
        if (shoreHeight < 1.0e8) {
            surfaceHeight = shoreHeight;
        }
    }
    if (surfaceHeight < -0.05 && position.y < surfaceHeight) {
        water.surface = position * (surfaceHeight / position.y);
    }
    water.measured = true;
    #endif
    return water;
}

#ifdef LITE_FAR_WATER_DETAILS
// Rippling sunlight on the ground under far water, as lite_apply_caustics does nearby, on a coarser
// two-block pattern that fades out before its lines get smaller than a pixel.
vec3 lite_far_caustics(vec3 color, LiteFarWater water, float footprint, float visibility) {
    float depth = water.surface.y - water.ground.y;
    if (!water.measured || depth < 0.05 || depth > 32.0) {
        return color;
    }
    bool moon = LiteShadowLightDirection.w > 0.5;
    float light = (moon ? 0.2 * (1.0 - LiteSunColor.a) : LiteSunColor.a) * (1.0 - 0.75 * LiteWeather.x);
    float fine = 1.0 - smoothstep(0.35, 0.9, footprint * 0.5);
    if (light <= 0.01 || fine <= 0.0) {
        return color;
    }
    vec3 towardLight = LiteShadowLightDirection.xyz;
    towardLight.y = max(towardLight.y, 0.25);
    vec2 p = (lite_water_anchored(water.ground) + towardLight.xz / towardLight.y * depth) * 0.5;
    float time = LiteTime.y;
    float first = 1.0 - smoothstep(0.02, 0.26, lite_water_cell_edges(p, time * 0.9));
    float second = 1.0 - smoothstep(0.02, 0.26, lite_water_cell_edges(p * 1.5 + 0.37, time * 1.1));
    float pattern = first * second + 0.25 * (first + second);
    float strength = pattern * light * visibility * exp(-depth * 0.15) * smoothstep(0.05, 0.6, depth) * fine;
    return color + color * LiteSunColor.rgb * strength * 0.6;
}

// Far water darkens and takes on its own tint with thickness, by the same rule as LiteLights' water
// nearby (lite_water_over_scene).
// Voxy: the ground before its water was drawn and the pixel after are both known, so the water's
// own colour comes out of vanilla's blend (its water texture is 180/255 opaque) and the near rule
// is applied to it as it stands. Distant Horizons: only the blended pixel is known, so its hue is
// kept and deepened.
const float LITE_FAR_WATER_ALPHA = 180.0 / 255.0;

vec3 lite_far_depth_colour(vec3 color, ivec2 pixel, LiteFarWater water) {
    if (!water.measured || !lite_water(LITE_WATER_BIOME_DEPTH)) {
        return color;
    }
    float thickness = clamp(length(water.ground) - length(water.surface), 0.0, 64.0);
    bool realistic = lite_water(LITE_WATER_REALISTIC) || lite_water(LITE_WATER_ENHANCED);
    float deep = 1.0 - exp(-thickness / (realistic ? 7.0 : 10.0));
    // LiteLights' water hands its depth look over to Voxy's see-through water at the border of its
    // own land (lite_water_depth_look); the far water starts from that look and deepens beyond it.
    float landEnd = LiteFeatureValuesA.y;
    if (landEnd > 0.0) {
        deep *= smoothstep(landEnd, landEnd * 1.5, length(water.surface.xz));
    }
    #ifndef LITE_FAR_SOURCE_DH
    if (lite_far_captured()) {
        vec3 ground = texelFetch(FarWaterA, pixel, 0).rgb;
        vec3 drawn = texelFetch(FarWaterB, pixel, 0).rgb;
        vec3 body = clamp((drawn - ground * (1.0 - LITE_FAR_WATER_ALPHA)) / LITE_FAR_WATER_ALPHA, 0.0, 1.0);
        vec3 hue = body / max(max(body.r, body.g), max(body.b, 0.05));
        float opacity;
        if (realistic) {
            vec3 deepColour = (hue * vec3(0.16, 0.30, 0.45) + vec3(0.0, 0.015, 0.03)) * max(max(body.r, body.g), body.b) * 1.6;
            body = mix(body, deepColour, deep);
            opacity = mix(0.3, 0.96, 1.0 - exp(-thickness / 3.5));
        } else {
            body *= mix(1.0, 0.55, deep);
            opacity = mix(LITE_FAR_WATER_ALPHA, 0.94, deep);
        }
        vec3 throughWater = mix(vec3(1.0), hue, min(1.0, 0.2 + thickness / 8.0) * (realistic ? 0.6 : 0.25));
        vec3 deepened = mix(ground * throughWater, body, opacity);
        // The pixel has been relit since Voxy drew it: carry that over.
        return deepened * (color / max(drawn, vec3(1.0 / 255.0)));
    }
    #endif
    float brightness = dot(color, vec3(0.2126, 0.7152, 0.0722));
    vec3 tint = color / max(max(color.r, color.g), max(color.b, 0.02));
    vec3 deepColour = realistic
        ? (tint * vec3(0.16, 0.30, 0.45) + vec3(0.0, 0.015, 0.03)) * brightness * 1.6
        : mix(color, tint * brightness, 0.5) * 0.55;
    return mix(color, deepColour, deep);
}

// Foam where far water meets its shore: only far terrain at the water's height counts as shore,
// so a hill in front of the water (a silhouette), a bridge or an overhang above it, LiteLights'
// own land at the border of the loaded chunks and the edge of the screen never make foam.
vec3 lite_far_edge_foam(vec3 color, ivec2 pixel, LiteFarWater water, float footprint, vec3 light) {
    // The shore is only known from the far mod's translucent pass.
    if (!lite_far_captured()) {
        return color;
    }
    float shoreHeight;
    float reach;
    float level = 1.5 + footprint * 2.0;
    lite_far_shore(pixel, water.ground.y, length(water.surface) - 2.0 - footprint * 4.0, 3, water.surface.y, level, shoreHeight, reach);
    if (shoreHeight > 1.0e8 || reach > 1.0e8 || abs(shoreHeight - water.surface.y) > level) {
        return color;
    }
    float distanceToShore = reach * footprint;
    vec2 anchored = lite_water_anchored(water.surface);
    float time = LiteTime.y;
    float noise = lite_water_value_noise(anchored * 0.5 + vec2(time * 0.2, -time * 0.15));
    float width = 0.5 + 0.5 * noise + footprint * 0.5;
    float foam = 1.0 - smoothstep(width * 0.5, width, distanceToShore);
    // Past a few blocks a pixel the line would only flicker: let it go.
    foam *= 1.0 - smoothstep(2.0, 5.0, footprint);
    // Where LiteLights' own land ends and Voxy's begins, far water meets near water, not a shore.
    float landEnd = LiteFeatureValuesA.y;
    if (landEnd > 0.0) {
        foam *= smoothstep(landEnd + 24.0, landEnd + 64.0, length(water.surface.xz));
    }
    return mix(color, LITE_FOAM_COLOUR * light, foam * 0.55);
}
#endif

#endif
