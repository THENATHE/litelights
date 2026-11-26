#version 330
#extension GL_ARB_separate_shader_objects : require

// The volumetric Dynamic clouds, first pass, at a fraction of the screen's resolution
// (render.feature.clouds.CloudVolume): each pixel's line of sight is stepped through the sky's
// cloud, from where it enters the clouds' heights to where it leaves them, meets the scene or
// passes the reach. The cloud at each step comes from the cloud field's column maps
// (clouds.volume.CloudMap, near and far, blended between two builds already): a soft span per
// band of sky, its edges worn into round billows by 3D noise, and the map read a little off to
// the side by more noise, so outlines lean, bulge and overhang. Each step is lit by the sun (or
// the moon) through the cloud toward it, read from the maps a few times along the way, with
// light scattered within the cloud and the sky's light from above. The result is the light the
// cloud sends along the line and how much of what lies behind shows through, with the distance
// at which the cloud first hides half of it, for the depth pass.
//
// Steps grow with distance and stride over empty sky; their number is fixed by the Cloud quality
// row, so no loop runs on data.

#include <litelights:post_common.glsl>
#include <litelights:skyfog/atmosphere.glsl>
#include <litelights:skyfog/lightning.glsl>
#include <litelights:clouds/volume.glsl>

uniform sampler2D SceneDepth;
uniform sampler2D NearMap;
uniform sampler2D FarMap;
uniform sampler2D CloudNoise;
uniform sampler2D CloudLumps;

layout(std140) uniform CloudVolumeInfo {
    vec4 NearPlace;    // xy the near map's corner, camera-relative; z its blocks per texel; w how far from its middle it is used whole (blocks)
    vec4 FarPlace;     // the same for the far map; w how far from the near map's middle the far one has taken over
    vec4 NearCentre;   // xy the near map's middle, camera-relative; z where the reach's fade starts, w the reach (blocks)
    vec4 CloudShape;   // x the floor, camera-relative y; y extinction per block in a solid cloud; z the noise's period (blocks); w the detail's reach (blocks)
    vec4 CloudColour;  // rgb the clouds' colour for the time of day; a moonlight's strength
    vec4 NoisePlace;   // xyz the camera in the noise's frame (cloud space, wrapped by the coarse warp's period, four of the billows'); w the frame's number (0 to 63), for the steps' start
    vec4 MarchSteps;   // x the most steps, y how much a step grows with distance, z light samples (1 to 4), w the shortest step (blocks)
};

layout(location = 0) in vec2 texCoord;
// rgb the light the cloud sends along the line, a: the whole number is the distance at which the
// cloud first hides half of what is behind (0: never), the fraction how much shows through.
layout(location = 0) out vec4 cloudOut;

const int MOST_STEPS = 160;
// Over empty sky a step is this many times longer.
const float EMPTY_STRIDE = 2.2;
const float LONGEST_STEP = 260.0;
// Each band's heights over the floor where it can hold cloud, so its texel is only read there.
const vec2 BAND_HEIGHTS[4] = vec2[](vec2(0.0, 1600.0), vec2(320.0, 820.0), vec2(680.0, 1200.0), vec2(1060.0, 1600.0));
// How deep the billows wear into each band's edges, and the finer heads after them: shallow enough
// that a heaped cloud keeps a full, rounded body with lobes on it rather than shreds, and the small
// alto and cirro puffs stay puffs rather than specks.
const float BAND_BILLOWS[4] = float[](0.62, 0.6, 0.45, 0.4);
const float BAND_HEADS[4] = float[](0.45, 0.35, 0.2, 0.2);
// How much each band's envelope swells before it is worn: the low clouds' bodies fill out above
// their base into bulbous masses instead of tapering to wedges.
const float BAND_SWELL[4] = float[](1.35, 1.15, 1.0, 1.0);
// How much of the warps each band takes: the low clouds less, so they lean without stretching.
const float BAND_WARP[4] = float[](0.6, 1.0, 1.0, 1.0);
// How dense each band's cloud is at most, against the low clouds': the high ones are thin.
const float BAND_THICKNESS[4] = float[](1.0, 0.85, 0.7, 0.3);
// How dark the creases between billows are against their heads.
const float BILLOW_SHADE = 0.85;
// The underside: a span's base sags unevenly by up to this share of its thickness (and never more
// than BASE_SAG_MOST blocks, nor under the floor), in broad swells and in the billows' lobes, and
// rounds in over BASE_ROUNDING of its thickness, so a cloud is a blob all the way round with its
// edge curling under rather than ending on a level plane.
const float BASE_SAG = 0.22;
const float BASE_SAG_MOST = 60.0;
const float BASE_ROUNDING = 0.25;
// How far the noise pushes the map's lookup about, in blocks, so outlines lean and overhang: a fine
// push with the billows, and a coarse one four times as wide that grows from nothing at the floor
// over the first COARSE_WARP_RISE blocks, so tall clouds lean and bulge while bases stay put.
const float WARP = 30.0;
const float COARSE_WARP = 48.0;
const float COARSE_WARP_RISE = 160.0;
// The fine heads' noise is this many times finer than the billows'.
const float HEADS_SCALE = 4.0;
// Toward the light: the samples share this much path (blocks), closer together near the point, so
// every Cloud quality step sees as far toward the sun and only the detail differs.
const float LIGHT_REACH = 400.0;
// Light on its way in is taken away by this share of the extinction seen along the line: what the
// cloud scatters forward still lights what lies behind.
const float LIGHT_EXTINCTION = 0.38;
// The heaped and storm clouds (the low band, all but stratus) are soft rolling billows, as in
// 1.1.3 (the owner, 1.1.8: the cellular lumps read as "voronoi noise solids", "not fluffy but
// rather kinda sharp"): the billows at twice their size (BIG_BILLOWS_PERIODS) carry the ordinary
// ones, BIG_BILLOW_SHARE of each, so big rolling forms bear smaller puffs with no cell size showing.
// The lumps atlas only gives the broad rise and fall of the base (its alpha, smooth gradient noise).
const float LUMPS_PERIODS = 2.0;
const float BIG_BILLOWS_PERIODS = 2.0;
const float BIG_BILLOW_SHARE = 0.6;
// A mass this thick (blocks) or less counts as small, this thick or more as big: a small one is
// worn SMALL_WEAR as deep, so it keeps its outline.
const vec2 LUMP_SIZE = vec2(50.0, 220.0);
const float SMALL_WEAR = 0.6;
// How deep the billows wear into a heaped cloud, how much it swells before that, and how dark the
// shallow hollows between billows are.
const float LUMP_WEAR = 0.62;
const float LUMP_SWELL = 1.4;
const float LUMP_SHADE = 0.86;
// The fine heads on a heaped cloud's edge, against the other clouds' (BAND_HEADS): a little fluff.
const float HEAPED_HEADS = 0.6;
// The underside of a heaped cloud follows its billows: a billow hangs below the base by up to this
// share of the cloud's thickness (and never more than LUMP_HANG_MOST blocks), a hollow draws the
// base up, and the broad gradient lifts and lowers whole stretches.
const float LUMP_HANG = 0.25;
const float LUMP_HANG_MOST = 80.0;
// A heaped cloud fades in over half its thickness below its top, but over no fewer and no more
// blocks than these (and six tenths of that above its base), so its edge is a deep soft fringe of
// vapour, a small cloud still fills and a big one's rim never does. It stands this share of its
// thickness higher to make up for the fade.
const vec2 LUMP_FRINGE = vec2(40.0, 80.0);
const float LUMP_RISE = 0.05;
// How the light at a point is made up: straight from the sun or moon through the cloud, scattered
// on within it (two terms reaching deeper and deeper), a floor of light that reaches anywhere in a
// lit cloud, and the sky's light from above, more at the top of a span than at its base.
const float DIRECT_SHARE = 0.55;
const float DEEP_LIGHT = 0.18;
const float SKY_SHARE = 0.55;

// The cloud's span over a point, the near map's or the far one's, blended where one hands over.
vec4 band(vec2 xz, int index) {
    float fromNear = length(xz - NearCentre.xy);
    if (fromNear <= NearPlace.w) {
        return lite_cloud_band(NearMap, NearPlace.xyz, xz, index);
    }
    vec4 far = lite_cloud_band(FarMap, FarPlace.xyz, xz, index);
    if (fromNear >= FarPlace.w) {
        return far;
    }
    vec4 near = lite_cloud_band(NearMap, NearPlace.xyz, xz, index);
    return mix(near, far, smoothstep(NearPlace.w, FarPlace.w, fromNear));
}

// The cloud's envelope at a point, no noise: for the light's samples, which only need its bulk.
// lowWarp is a heaped cloud's warp at the lit point, taken for the low band's samples too, so its
// light follows the outline it is drawn with rather than changing sharply at the map's own rim.
float envelopeAt(vec3 position, vec2 lowWarp) {
    float height = position.y - CloudShape.x;
    float densest = 0.0;
    for (int i = 0; i < 4; i++) {
        if (height < BAND_HEIGHTS[i].x || height > BAND_HEIGHTS[i].y) {
            continue;
        }
        vec2 xz = i == 0 ? position.xz + lowWarp : position.xz;
        densest = max(densest, lite_cloud_envelope(band(xz, i), height) * BAND_THICKNESS[i]);
    }
    return densest;
}

// A heaped cloud's base over a column, lowered under lumps and raised at clefts. It may come down
// toward the floor but eases off as it nears it, so it never lies flat along the floor.
float heapedBase(float bottom, float thickness, float lumps, float broad) {
    float reach = min(LUMP_HANG * thickness, LUMP_HANG_MOST);
    float hang = reach * (1.3 * (lumps - 0.5) + 0.3 * (broad - 0.5));
    if (hang <= 0.0) {
        return bottom - hang;
    }
    float room = max(bottom - 2.0, 1.0);
    return bottom - room * (1.0 - exp(-hang / room));
}

// The cloud at a point: x its density (0..1 of a solid low cloud's), y its storminess, z how high
// in its span the point is, w how much light reaches into it there (less in the creases between
// billows, and less again in the clefts between lumps). detail
// fades the fine heads out with distance; worn fades the billows' wear out further off, where the
// steps are long and a cloud is a few columns of the far map wide, so a distant cloud keeps its soft
// body rather than breaking into specks.
// lightWarp is the warp the light's samples take for the low band (envelopeAt).
vec4 cloudAt(vec3 position, float detail, float worn, out vec2 lightWarp) {
    float height = position.y - CloudShape.x;
    vec4 noise = lite_cloud_noise(CloudNoise, (position + NoisePlace.xyz) / CloudShape.z);
    vec2 coarse = lite_cloud_noise(CloudNoise, (position + NoisePlace.xyz) / (CloudShape.z * 4.0)).ba;
    vec2 warp = (noise.ba - 0.5) * (2.0 * WARP) + (coarse - 0.5) * (2.0 * COARSE_WARP) * smoothstep(0.0, COARSE_WARP_RISE, height);
    lightWarp = vec2(0.0);
    float billowShade = mix(BILLOW_SHADE, 1.0, noise.r);
    vec4 cloud = vec4(0.0, 0.0, 0.0, billowShade);
    for (int i = 0; i < 4; i++) {
        if (height < BAND_HEIGHTS[i].x || height > BAND_HEIGHTS[i].y) {
            continue;
        }
        vec4 span = band(position.xz + warp * BAND_WARP[i], i);
        if (span.b < 0.004) {
            continue;
        }
        float bottom = span.r * LITE_CLOUD_HEIGHT_RANGE;
        float top = span.g * LITE_CLOUD_HEIGHT_RANGE;
        float thickness = max(top - bottom, 1.0);
        // The low band's alpha says whether the cloud is heaped, from 128 of 255 up, and how stormy
        // above that (CloudMap.HEAPED); stratus is 0.
        float heaped = i == 0 ? smoothstep(0.2, 0.45, span.a) : 0.0;
        if (i == 0) {
            lightWarp = warp * (BAND_WARP[0] * heaped);
        }
        float storm = i == 0 ? clamp((span.a * 255.0 - 128.0) / 127.0, 0.0, 1.0) : span.a;
        float crease = noise.r;
        float shade = billowShade;
        float base = max(bottom - min(BASE_SAG * thickness, BASE_SAG_MOST) * (0.3 * coarse.x + 0.7 * noise.r), 2.0);
        float swell = BAND_SWELL[i];
        float billows = BAND_BILLOWS[i];
        float rounded = smoothstep(base, base + max(LITE_CLOUD_BASE_SOFTNESS, BASE_ROUNDING * thickness), height)
            * (1.0 - smoothstep(top - thickness * LITE_CLOUD_TOP_SOFTNESS, top, height));
        if (heaped > 0.0) {
            float broad = lite_cloud_noise(CloudLumps, (position + NoisePlace.xyz) / (CloudShape.z * LUMPS_PERIODS)).a;
            float big = lite_cloud_noise(CloudNoise, (position + NoisePlace.xyz) / (CloudShape.z * BIG_BILLOWS_PERIODS)).r;
            // A small mass is worn less deep, so it keeps its own outline instead of dissolving.
            float size = smoothstep(LUMP_SIZE.x, LUMP_SIZE.y, thickness);
            float lumps = mix(noise.r, big, BIG_BILLOW_SHARE * mix(0.5, 1.0, size));
            // A heaped cloud fades in over a set depth from its top and base rather than a share of
            // its thickness, so a thin rim never fills solid: the lumps wear its sides into lobes
            // as they do its top, where a share of a rim's thickness left a sheer wall that stood
            // as a straight upright line. Raised a little so the lumps heap up as turrets.
            float heapedTop = top + LUMP_RISE * thickness;
            float heapedBottom = heapedBase(bottom, thickness, lumps, broad);
            float fringe = clamp(0.5 * thickness, LUMP_FRINGE.x, LUMP_FRINGE.y);
            float heapedRounded = smoothstep(heapedBottom, heapedBottom + fringe * 0.6, height)
                * (1.0 - smoothstep(heapedTop - fringe, heapedTop, height));
            rounded = mix(rounded, heapedRounded, heaped);
            crease = mix(crease, lumps, heaped);
            shade = mix(shade, mix(LUMP_SHADE, 1.0, lumps), heaped);
            swell = mix(swell, LUMP_SWELL, heaped);
            billows = mix(billows, LUMP_WEAR * mix(SMALL_WEAR, 1.0, size), heaped);
        }
        float envelope = min(span.b * rounded * swell, 1.0);
        if (envelope <= 0.0) {
            continue;
        }
        float within = lite_cloud_height_in(span, height);
        // Billows (or lumps) wear the edges and the underside alike, so lobes hang below a cloud as they bulge from its sides.
        float wear = billows * (1.0 - crease) * worn;
        // Worn through a smooth step rather than a straight cut, so the billows' edges round off
        // into soft fringes with no crease where the wear starts.
        float density = smoothstep(0.0, 1.0, (envelope - wear) / max(1.0 - wear, 0.05));
        if (density > 0.0 && density < 1.0 && detail > 0.0) {
            float heads = lite_cloud_noise(CloudNoise, (position + NoisePlace.xyz) * (HEADS_SCALE / CloudShape.z)).g;
            float fine = BAND_HEADS[i] * detail * (1.0 - heads) * mix(1.0, HEAPED_HEADS, heaped);
            density = smoothstep(0.0, 1.0, (density - fine) / max(1.0 - fine, 0.05));
        }
        // Thin at the fringe and solid inside, so edges are soft rather than cut.
        density *= density * BAND_THICKNESS[i];
        if (density > cloud.x) {
            cloud = vec4(density, storm, within, shade);
        }
    }
    return cloud;
}

// Henyey-Greenstein's phase, against light scattered evenly (1).
float phase(float cosine, float g) {
    float g2 = g * g;
    return (1.0 - g2) / pow(max(1.0 + g2 - 2.0 * g * cosine, 1.0e-4), 1.5);
}

void main() {
    vec3 eye = lite_view_origin();
    vec3 ray = lite_view_direction(texCoord);
    ivec2 screen = textureSize(SceneDepth, 0);
    int scale = int(round(float(screen.x) * texCoord.x / gl_FragCoord.x));
    float depth = lite_cloud_farthest_depth(SceneDepth, ivec2(gl_FragCoord.xy), clamp(scale, 1, 4), screen);
    float sceneDistance = depth > 0.0 ? length(lite_position_from_depth(texCoord, depth) - eye) : 1.0e9;
    cloudOut = vec4(0.0, 0.0, 0.0, 0.998);

    // The part of the line inside the clouds' heights.
    float low = CloudShape.x;
    float high = CloudShape.x + LITE_CLOUD_HEIGHT_RANGE;
    float start = 0.0;
    float end = 1.0e9;
    if (abs(ray.y) > 1.0e-5) {
        float toLow = (low - eye.y) / ray.y;
        float toHigh = (high - eye.y) / ray.y;
        start = max(min(toLow, toHigh), 0.0);
        end = max(toLow, toHigh);
    } else if (eye.y < low || eye.y > high) {
        return;
    }
    end = min(end, sceneDistance);
    end = min(end, NearCentre.w / max(length(ray.xz), 1.0e-4));
    if (end <= start) {
        return;
    }

    vec3 lightDirection = LiteSunDirection.y > -0.05 ? LiteSunDirection.xyz : LiteMoonDirection.xyz;
    float daylight = LiteSunColor.a;
    float moonUp = clamp(LiteMoonDirection.w * 4.0, 0.0, 1.0) * LiteAmbientColor.a;
    // The light from the sun or moon, and the sky's from above, before the cloud's own shade.
    vec3 sunlit = mix(vec3(1.0), LiteSunColor.rgb, 0.6) + LiteSunGlow.rgb * 0.8 * (1.0 - daylight * 0.6);
    vec3 direct = LiteSunDirection.y > -0.05 ? CloudColour.rgb * sunlit * 0.85 : vec3(0.07, 0.08, 0.11) * 2.2 * CloudColour.a * moonUp;
    vec3 skylight = CloudColour.rgb * mix(vec3(0.70, 0.74, 0.82), LiteHazeColor.rgb * 1.1, 0.35);
    float cosine = dot(ray, lightDirection);
    float scatter = mix(phase(cosine, 0.6), phase(cosine, -0.2), 0.4);
    bool flashes = LiteLightning[1].w > 0.0 || LiteLightning[2].w > 0.0;
    int lightSamples = int(clamp(MarchSteps.z, 1.0, 4.0));

    // A different start within the first step per pixel, and per frame, so the steps don't show as
    // bands and the resolve pass (volume_resolve.fsh) averages their pattern away.
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))) + NoisePlace.w * 0.61803399);
    float t = start + jitter * max(MarchSteps.w, start * MarchSteps.y);
    float through = 1.0;
    vec3 light = vec3(0.0);
    float front = 0.0;
    float weighted = 0.0;
    float weights = 0.0;
    int steps = int(clamp(MarchSteps.x, 8.0, float(MOST_STEPS)));
    // Whether the last step strode over empty sky: on meeting cloud it steps back and comes in
    // again at the ordinary pace, so every pixel enters a cloud at about the same depth.
    bool striding = false;
    float lastStride = 0.0;
    for (int i = 0; i < MOST_STEPS; i++) {
        if (i >= steps || t >= end || through < 0.01) {
            break;
        }
        float stepLength = clamp(t * MarchSteps.y, MarchSteps.w, LONGEST_STEP);
        vec3 position = eye + ray * t;
        float fade = 1.0 - smoothstep(NearCentre.z, NearCentre.w, length(position.xz));
        float detail = 1.0 - smoothstep(CloudShape.w * 0.6, CloudShape.w, t);
        float worn = 1.0 - 0.75 * smoothstep(CloudShape.w, CloudShape.w * 4.0, t);
        vec2 lightWarp = vec2(0.0);
        vec4 cloud = fade > 0.0 ? cloudAt(position, detail, worn, lightWarp) : vec4(0.0);
        float density = cloud.x * fade;
        if (density <= 0.0) {
            lastStride = stepLength * EMPTY_STRIDE;
            striding = true;
            t += lastStride;
            continue;
        }
        if (striding) {
            striding = false;
            t = max(t - lastStride + stepLength, start);
            continue;
        }
        float storm = cloud.y;
        float within = cloud.z;
        // How much cloud lies between the point and the light.
        float toLight = 0.0;
        for (int j = 0; j < 4; j++) {
            if (j >= lightSamples) {
                break;
            }
            float from = float(j) / float(lightSamples);
            float to = float(j + 1) / float(lightSamples);
            float at = LIGHT_REACH * 0.5 * (from * from + to * to);
            toLight += envelopeAt(position + lightDirection * at, lightWarp) * LIGHT_REACH * (to * to - from * from);
        }
        toLight *= CloudShape.y * LIGHT_EXTINCTION;
        float lit = exp(-toLight) * scatter + 0.5 * exp(-toLight * 0.2) + 0.25 * exp(-toLight * 0.05);
        // A storm is dark low down; its towering top is lit as any cloud's.
        float stormShade = 1.0 - storm * mix(0.62, 0.08, within);
        // The creases between heads lie in their own shade, so the billows read as heads.
        float crease = cloud.w;
        vec3 shine = (direct * (DIRECT_SHARE * lit + DEEP_LIGHT) * crease
            + skylight * SKY_SHARE * mix(0.7, 1.0, within) * mix(1.0, crease, 0.5)) * stormShade;
        if (flashes) {
            shine += lite_flash_cloud(position) * 2.2 * (1.0 - 0.45 * within);
        }
        float extinction = density * CloudShape.y;
        float stepThrough = exp(-extinction * stepLength);
        float taken = through * (1.0 - stepThrough);
        light += shine * taken;
        weighted += taken * t;
        weights += taken;
        through *= stepThrough;
        if (front == 0.0 && through < 0.5) {
            front = max(t, 1.0);
        }
        t += stepLength;
    }
    if (weights <= 1.0e-5) {
        return;
    }
    // The air between: the cloud sinks into the haze along the same line of sight as the land
    // below it, and into a storm's fog.
    float seen = weighted / weights;
    vec3 middle = ray * seen;
    if (lite_feature(LITE_FEATURE_LITE_FOG)) {
        float haze = lite_feature(LITE_FEATURE_HAZE) ? 1.0 - exp(-lite_haze_depth(middle, seen)) : 0.0;
        float storm = lite_feature(LITE_FEATURE_STORM_FOG) ? 1.0 - exp(-lite_storm_depth(seen)) : 0.0;
        float covered = clamp(max(haze, storm), 0.0, 0.9);
        vec3 air = mix(lite_horizon_color(ray), LiteStormFog.rgb, storm / max(haze + storm, 1.0e-4));
        light = mix(light, air * (1.0 - through), covered);
    }
    cloudOut = vec4(light, floor(front) + min(through, 0.998));
}
