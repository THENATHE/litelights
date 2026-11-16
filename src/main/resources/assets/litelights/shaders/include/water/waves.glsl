#ifndef LITELIGHTS_WATER_WAVES_GLSL
#define LITELIGHTS_WATER_WAVES_GLSL

#include <litelights:frame.glsl>
#include <litelights:pixel_lock.glsl>
#include <litelights:water/uniforms.glsl>
#include <litelights:water/common.glsl>

// Ocean waves (Experimental; docs/DECISIONS.md, waves17). Shading only: the water's mesh never
// moves, so nothing changes for Sodium, vanilla or the far renderers, and no crack can open
// against the shore.
//
// Out at sea, long swell lines travel with the wind (LiteWaveTrains, see OceanSwell). Within about
// twenty blocks of the shore they hand over to crests parallel to the waterline, coming straight in
// (their phase is the shore field's distance), which sharpen, break into a band of foam that
// rushes in, and run up the beach as a thin sheet of water (the swash, drawn on the beach's own top
// faces), which slides back and leaves the sand dark for a moment. Everything sits on the
// 16-per-block texel grid, banded and dithered like the rest of the pixel art.

const float LITE_WAVE_TAU = 6.28318530718;
// Patterns along the shore and the swell's groups: whole multiples of 2*pi/2048, so they repeat
// with the 2048-block anchor (see lite_water_anchored).
const float LITE_WAVE_K = LITE_WAVE_TAU / 2048.0;

struct LiteSwell {
    float sea;      // 0..1, how much swell this point has (0: leave the water as it is)
    float crest;    // 0 in a trough, 1 on top of a crest, shaped (narrow crests, broad troughs)
    vec2 slope;     // the swell's surface slope, which tilts the reflections
    float foam;     // 1 on a foam texel
};

// A 4 x 4 ordered dither on the texel grid, so soft edges turn into whole texels.
float lite_wave_dither(ivec2 texel) {
    const int BAYER[16] = int[16](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
    return (float(BAYER[(texel.x & 3) + (texel.y & 3) * 4]) + 0.5) / 16.0;
}

// Sets the waves arriving along a shore a little apart in time (radians, up to a quarter wave either
// way, changing over thirty to forty blocks), so the crests curve and don't all land at once.
float lite_wave_along_shore(vec2 anchored) {
    return 0.8 * (sin(dot(anchored, vec2(61.0, 23.0) * LITE_WAVE_K)) + sin(dot(anchored, vec2(-19.0, 57.0) * LITE_WAVE_K) + 1.7));
}

// How far from the camera the swell fades out, before its lines would turn to shimmer.
float lite_wave_distance_fade(vec3 position) {
    return 1.0 - smoothstep(80.0, 120.0, length(position.xz));
}

float lite_wave_map_fade(ivec2 worldColumn) {
    ivec2 inMap = worldColumn - LiteWaterFlags.yz;
    int toEdge = min(min(inMap.x, inMap.y), min(255 - inMap.x, 255 - inMap.y));
    return smoothstep(0.0, 12.0, float(toEdge));
}

// Foam is drawn in whole texels: solid where it is strong, dithered only over a narrow edge, so it
// reads as clean pixel-art foam rather than a speckle.
float lite_wave_foam_texel(float foam, float dither) {
    return step(0.3 + 0.4 * dither, foam);
}

LiteSwell lite_no_swell() {
    return LiteSwell(0.0, 0.0, vec2(0.0), 0.0);
}

// The swell on a water surface. surfacePosition is the pixel-locked position of the top face.
LiteSwell lite_ocean_swell(vec3 surfacePosition) {
    LiteSwell swell = lite_no_swell();
    ivec2 worldColumn = lite_water_world_column(surfacePosition - vec3(0.0, 0.02, 0.0));
    LiteFluidColumn column = lite_fluid_column(worldColumn);
    if (!column.present || column.lava || abs(column.surfaceY - surfacePosition.y) > 0.3) {
        return swell;
    }
    LiteShoreField field = lite_shore_field(surfacePosition);
    swell.sea = field.strength * column.inMap * lite_wave_distance_fade(surfacePosition);
    if (swell.sea < 0.004) {
        swell.sea = 0.0;
        return swell;
    }
    float state = LiteWaveInfo.x;
    vec2 anchored = lite_water_anchored(surfacePosition);

    // Out at sea: the trains that follow the wind.
    float windHeight = 0.0;
    vec2 windSlope = vec2(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 train = LiteWaveTrains[i];
        float phase = dot(train.xy, anchored) - train.z;
        windHeight += train.w * cos(phase);
        windSlope -= train.w * sin(phase) * train.xy;
    }
    // The swell comes in groups: a few higher waves, then a few lower ones, rolling with the wind.
    float groups = 0.8 + 0.2 * sin(dot(anchored, vec2(23.0, -41.0) * LITE_WAVE_K) - LiteTime.y * (LITE_WAVE_TAU * 30.0 / 3600.0));

    // Near the shore: crests along the waterline, coming straight in. The phase grows with the
    // distance out, so a crest travels toward the shore as the shore phase grows.
    float shorePhase = LiteWaveInfo.z * max(field.distance, 0.0) + LiteWaveInfo.y + lite_wave_along_shore(anchored);
    float shoreHeight = cos(shorePhase);
    vec2 shoreSlope = sin(shorePhase) * LiteWaveInfo.z * field.toShore;
    float nearShore = (1.0 - smoothstep(5.0, 22.0, field.distance)) * step(1.0e-3, dot(field.toShore, field.toShore));
    float height = mix(windHeight * groups, shoreHeight, nearShore);

    // Storm waves break further out. The swell grows as the water shallows toward the break, and
    // spends itself in the surf.
    float breakAt = mix(1.5, 3.5, state);
    float shoaling = 1.0 + 0.5 * nearShore;
    float spent = mix(0.35, 1.0, smoothstep(0.0, breakAt, field.distance));
    float amplitude = (0.07 + 0.2 * state) * swell.sea * shoaling * spent;
    swell.slope = mix(windSlope * groups, shoreSlope, nearShore) * amplitude;
    float rise = clamp(0.5 + 0.5 * height, 0.0, 1.0);
    swell.crest = pow(rise, mix(1.4, 3.0, nearShore));

    ivec2 texel = ivec2(floor(anchored * 16.0));
    float dither = lite_wave_dither(texel);
    // Whitecaps: in a strong wind the tops of the crests break at sea too.
    float whitecaps = smoothstep(0.45, 0.95, state) * (1.0 - 0.5 * nearShore);
    float chop = lite_water_value_noise(anchored + LiteWaveWind.xy * (LiteTime.y * 0.8));
    float caps = smoothstep(0.86, 0.97, swell.crest) * whitecaps * smoothstep(0.45, 0.75, chop);
    // The crest whitens as it reaches the break line...
    float breaking = (1.0 - smoothstep(breakAt, breakAt + 2.5, field.distance)) * smoothstep(0.55, 0.9, swell.crest);
    // ...and a band of foam rushes in behind it, leaving lace that thins until the next one.
    // behind is 0 on a crest and grows seaward of it, to 1 at the next crest out.
    float behind = fract(shorePhase / LITE_WAVE_TAU);
    float bore = 1.0 - smoothstep(0.0, mix(0.07, 0.2, state), behind);
    float lace = smoothstep(0.55, 0.85, lite_water_value_noise(anchored * 2.0)) * (1.0 - behind) * (1.0 - behind);
    float surf = (1.0 - smoothstep(breakAt * 0.7, breakAt + 0.5, field.distance)) * max(bore, lace * 0.7) * mix(0.75, 1.0, state);
    float foam = max(max(caps, breaking * nearShore), surf * nearShore) * min(1.0, swell.sea * 1.5);
    swell.foam = lite_wave_foam_texel(foam, dither);
    return swell;
}

// The swell's light on the water: banded crests a little brighter and greener (light through their
// thin tops), troughs a little darker, and foam.
vec3 lite_ocean_swell_colour(vec3 seen, LiteSwell swell, vec3 light, vec3 surfacePosition) {
    float dither = lite_wave_dither(ivec2(floor(lite_water_anchored(surfacePosition) * 16.0)));
    float bands = floor(swell.crest * 4.0 + dither) / 4.0;
    float state = LiteWaveInfo.x;
    seen *= 1.0 + (bands - 0.35) * (0.16 + 0.1 * state) * swell.sea;
    seen = mix(seen, seen * vec3(0.92, 1.1, 1.06), bands * 0.35 * swell.sea);
    return mix(seen, vec3(0.90, 0.95, 1.0) * light, swell.foam * 0.85);
}

// The swash: a thin sheet of water and foam that runs up the beach after each wave and slides back,
// on the top faces of the beach blocks at the water's level (sand, gravel, stone, anything).
void lite_ocean_swash(inout vec4 color, LiteSurface surface) {
    if (surface.normal.y < 0.5 || lite_feature(LITE_FEATURE_CAMERA_UNDERWATER)) {
        return;
    }
    vec3 position = lite_locked(LITE_LOCK_WATER_SURFACE) ? lite_water_lock_horizontal(surface.position) : surface.position;
    ivec2 worldColumn = lite_water_world_column(position - vec3(0.0, 0.02, 0.0));
    // Only beach columns carry a swell strength on land: everything else returns after one fetch.
    if (!lite_fluid_map_contains(worldColumn) || lite_shore_texel(worldColumn).a == 0) {
        return;
    }
    LiteShoreField field = lite_shore_field(position);
    float run = -field.distance;
    float sea = field.strength * lite_wave_map_fade(worldColumn) * lite_wave_distance_fade(position);
    if (run < 0.0 || run > 3.0 || sea < 0.01 || field.toShore == vec2(0.0)) {
        return;
    }
    // The water the swash comes from: its surface must be just under this face.
    vec3 seaward = position - vec3(field.toShore.x, 0.0, field.toShore.y) * (run + 0.6);
    LiteFluidColumn water = lite_fluid_column(lite_water_world_column(seaward));
    float above = surface.position.y - water.surfaceY;
    if (!water.present || water.lava || above < -0.1 || above > 0.5) {
        return;
    }

    float state = LiteWaveInfo.x;
    vec2 anchored = lite_water_anchored(position);
    // Each wave reaches the waterline when the shore phase there passes a whole wave (u = 0).
    float waves = (LiteWaveInfo.y + lite_wave_along_shore(anchored)) / LITE_WAVE_TAU;
    float wave = floor(waves);
    float u = waves - wave;
    // Some waves run further than others, and further on some stretches of the beach.
    float reach = sea * mix(0.8, 2.6, state) * (0.6 + 0.4 * lite_water_hash(ivec2(int(wave) & 1023, 77)))
        * (0.8 + 0.4 * lite_water_value_noise(anchored * 0.5));
    // The front runs up quickly, slows, and slides back down more slowly.
    float front = u < 0.3 ? reach * sin(1.5708 * u / 0.3) : reach * (1.0 - smoothstep(0.3, 0.95, u));
    float ragged = (lite_water_value_noise(anchored * 4.0 + vec2(wave * 3.0, 0.0)) - 0.5) * 0.3 * min(front, 1.0);
    float edge = front + ragged - run;
    float covered = step(0.0, edge) * step(0.02, front);
    float draining = smoothstep(0.3, 0.95, u);

    // How bright the light here is, from the shaded colour over the texture, for the foam.
    vec3 texel = textureLod(LiteWaterAtlas, surface.uv, 0.0).rgb;
    float light = dot(color.rgb, vec3(0.299, 0.587, 0.114)) / max(dot(texel, vec3(0.299, 0.587, 0.114)), 0.04);

    // Wet sand: dark where this wave has been until it drains, and always a little at the waterline.
    float highWater = u < 0.3 ? front : reach;
    float wet = step(run, highWater) * (1.0 - smoothstep(0.3, 1.0, u));
    float damp = 1.0 - smoothstep(0.0, 0.5 + 0.5 * state, run);
    vec3 ground = color.rgb * (1.0 - 0.25 * max(wet, damp * 0.6) * min(1.0, sea * 1.5));
    // The sheet: the beach seen through a little blue-green water that catches the sky, clearer
    // as it drains.
    float sheet = covered * mix(0.6, 0.3, draining) * min(1.0, sea * 2.0);
    vec3 thinWater = mix(ground * vec3(0.6, 0.8, 0.95), (vec3(0.28, 0.54, 0.78) + LiteWaterSkyColor.rgb * 0.2) * light, 0.4);
    ground = mix(ground, thinWater, sheet);
    // Foam on the running front, and lace on the sheet while it runs up.
    float frontFoam = covered * (1.0 - smoothstep(0.0, 0.18 + 0.1 * state, edge)) * (u < 0.55 ? 1.0 : 0.4);
    float laceFoam = covered * smoothstep(0.55, 0.8, lite_water_value_noise(anchored * 2.0 + vec2(0.0, wave * 5.0))) * (1.0 - draining) * 0.8;
    float foam = max(frontFoam, laceFoam) * min(1.0, sea * 1.5);
    float foamTexel = lite_wave_foam_texel(foam, lite_wave_dither(ivec2(floor(anchored * 16.0))));
    color.rgb = mix(ground, vec3(0.90, 0.95, 1.0) * light, foamTexel * 0.9);
}

#endif
