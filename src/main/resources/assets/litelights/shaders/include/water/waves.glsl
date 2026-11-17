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
    float foam;     // 1 on a foam texel (or, where texels are smaller than a pixel, the share of them)
    float fine;     // 1 where a texel covers a pixel or more, falling to 0 where many share a pixel
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

// How many blocks one screen pixel covers on a level surface here: x across the view, y along it,
// where a low view stretches the pixel. From the projection's focal length and the screen height,
// so it needs no derivatives and works inside branches.
vec2 lite_wave_footprint(vec3 position) {
    vec3 view = position - lite_view_origin();
    float range = max(length(view), 1.0e-3);
    float focal = length(vec3(LiteViewProjection[0][1], LiteViewProjection[1][1], LiteViewProjection[2][1]));
    float across = range * 2.0 / max(focal * LiteScreen.y, 1.0);
    return vec2(across, across / max(abs(view.y) / range, 0.05));
}

// 1 where a 1/16 block texel covers a pixel or more, 0 where three or more texels share one. The
// texel-grid details (the dither, the bands' steps, the grain) give way to their average there,
// which is what the pixel would show, instead of beating against the screen's grid into stripes.
float lite_wave_fine(vec2 footprint) {
    float texelsPerPixel = sqrt(footprint.x * footprint.y) * 16.0;
    return 1.0 - smoothstep(0.6, 1.6, texelsPerPixel);
}

// A subtle grain for foam and the swell's light, two texels' hash mixed so it has a little
// structure: 0.93 to 1.05 times the colour, and 1 where texels are smaller than a pixel.
float lite_wave_grain(vec2 anchored, float fine) {
    ivec2 texel = ivec2(floor(anchored * 16.0));
    float grain = 0.6 * lite_water_hash(texel) + 0.4 * lite_water_hash((texel >> 1) + ivec2(733, 211));
    return 1.0 + (grain - 0.6) * 0.12 * fine;
}

// The foam's colour, grained: for the surf, the swash and edge foam alike.
vec3 lite_foam_grained(vec3 foamColour, vec3 position) {
    return foamColour * lite_wave_grain(lite_water_anchored(position), lite_wave_fine(lite_wave_footprint(position)));
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

// The same, giving way to the share of foam texels where texels are smaller than a pixel.
float lite_wave_foam_cover(float foam, float dither, float fine) {
    return mix(clamp((foam - 0.3) / 0.4, 0.0, 1.0), lite_wave_foam_texel(foam, dither), fine);
}

// The player's Wave foam setting: how thick the surf's and the swash's foam lines are (1 as drawn by
// default). Only the ocean waves read it; the wakes share lite_wave_foam_texel but not this.
float lite_wave_foam_thickness() {
    return LiteWaveWind.z;
}

// The crest for its foam (whitecaps, the breaking crest), so the whitened band widens or narrows
// with the foam setting: near the top the crest falls with the square of the distance from it, so
// a power of 1/t^2 scales the band's width by t. At the default the crest is used as it is.
float lite_wave_foam_crest(float crest) {
    float thickness = lite_wave_foam_thickness();
    return thickness == 1.0 ? crest : pow(crest, 1.0 / (thickness * thickness));
}

LiteSwell lite_no_swell() {
    return LiteSwell(0.0, 0.0, vec2(0.0), 0.0, 1.0);
}

// Two value noises for the price of one: each lattice point's hash gives both (lite_water_hash2).
vec2 lite_wave_noise2(vec2 p) {
    ivec2 cell = ivec2(floor(p));
    vec2 f = fract(p);
    vec2 s = f * f * (3.0 - 2.0 * f);
    vec2 a = lite_water_hash2(cell);
    vec2 b = lite_water_hash2(cell + ivec2(1, 0));
    vec2 c = lite_water_hash2(cell + ivec2(0, 1));
    vec2 d = lite_water_hash2(cell + ivec2(1, 1));
    return mix(mix(a, b, s.x), mix(c, d, s.x), s.y);
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
    vec2 footprint = lite_wave_footprint(surfacePosition);
    swell.fine = lite_wave_fine(footprint);

    // Out at sea: the trains that follow the wind. Neighbouring trains (even and odd) each take their
    // own gusts, patches of 32 and 64 blocks drifting with the wind like cloud shadows or wind running
    // through grass (0.25 to 1.35 times their share, about 0.8 on average, as the old even groups
    // were); their own wander of phase (about 3 radians across 32 to 64 blocks, turning the crests by
    // up to ten degrees); and their own breaks along the crest every 16 blocks or so. The sea gets wave
    // groups and calm patches and short crests, as real seas have, instead of long parallel lines that
    // read as stripes from high up. Driven by the shaders' clock, so it all stops when that does.
    vec2 drift = LiteWaveWind.xy * LiteTime.y;
    vec2 near = lite_wave_noise2(anchored / 32.0 + vec2(71.1, 9.7) - drift * 0.1);
    vec2 wide = lite_wave_noise2(anchored / 64.0 + vec2(17.3, 41.9) - drift * 0.05);
    vec2 crests = lite_wave_noise2(anchored / 16.0 + vec2(91.7, 13.3) - drift * 0.08);
    vec2 gusts = vec2(near.x, mix(near.x, wide.x, 0.7));
    vec2 wander = (vec2(wide.y, near.y) - 0.5) * 3.2;
    vec2 shares = (0.25 + 1.1 * smoothstep(0.2, 0.8, gusts)) * (0.35 + 1.3 * smoothstep(0.25, 0.75, crests));
    float windHeight = 0.0;
    vec2 windSlope = vec2(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 train = LiteWaveTrains[i];
        bool even = (i % 2) == 0;
        float phase = dot(train.xy, anchored) - train.z + (even ? wander.x : wander.y);
        float share = train.w * (even ? shares.x : shares.y);
        windHeight += share * cos(phase);
        windSlope -= share * sin(phase) * train.xy;
    }
    // Waves much shorter than a few pixels would alias into stripes: they fade as the pixel along
    // the view grows past a quarter of their length (the swell's 12 blocks, the shore's 8).
    float windSeen = 1.0 - smoothstep(2.0, 5.0, footprint.y);
    float shoreSeen = 1.0 - smoothstep(1.5, 3.5, footprint.y);
    windHeight *= windSeen;
    windSlope *= windSeen;

    // Near the shore: crests along the waterline, coming straight in. The phase grows with the
    // distance out, so a crest travels toward the shore as the shore phase grows.
    float shorePhase = LiteWaveInfo.z * max(field.distance, 0.0) + LiteWaveInfo.y + lite_wave_along_shore(anchored);
    float shoreHeight = cos(shorePhase) * shoreSeen;
    vec2 shoreSlope = sin(shorePhase) * LiteWaveInfo.z * field.toShore * shoreSeen;
    float nearShore = (1.0 - smoothstep(5.0, 22.0, field.distance)) * step(1.0e-3, dot(field.toShore, field.toShore));
    float height = mix(windHeight, shoreHeight, nearShore);

    // Storm waves break further out. The swell grows as the water shallows toward the break, and
    // spends itself in the surf.
    float breakAt = mix(1.5, 3.5, state);
    float shoaling = 1.0 + 0.5 * nearShore;
    float spent = mix(0.35, 1.0, smoothstep(0.0, breakAt, field.distance));
    float amplitude = (0.07 + 0.2 * state) * swell.sea * shoaling * spent;
    swell.slope = mix(windSlope, shoreSlope, nearShore) * amplitude;
    float rise = clamp(0.5 + 0.5 * height, 0.0, 1.0);
    swell.crest = pow(rise, mix(1.4, 3.0, nearShore));

    ivec2 texel = ivec2(floor(anchored * 16.0));
    float dither = lite_wave_dither(texel);
    float thickness = lite_wave_foam_thickness();
    float foamCrest = lite_wave_foam_crest(swell.crest);
    // Whitecaps: in a strong wind the tops of the crests break at sea too.
    float whitecaps = smoothstep(0.45, 0.95, state) * (1.0 - 0.5 * nearShore);
    float chop = lite_water_value_noise(anchored + LiteWaveWind.xy * (LiteTime.y * 0.8));
    float caps = smoothstep(0.86, 0.97, foamCrest) * whitecaps * smoothstep(0.45, 0.75, chop);
    // The crest whitens as it reaches the break line...
    float breaking = (1.0 - smoothstep(breakAt, breakAt + 2.5, field.distance)) * smoothstep(0.55, 0.9, foamCrest);
    // ...and a band of foam rushes in behind it, leaving lace that thins until the next one.
    // behind is 0 on a crest and grows seaward of it, to 1 at the next crest out.
    float behind = fract(shorePhase / LITE_WAVE_TAU);
    float bore = 1.0 - smoothstep(0.0, mix(0.07, 0.2, state) * thickness, behind);
    float lace = smoothstep(0.55, 0.85, lite_water_value_noise(anchored * 2.0)) * (1.0 - behind) * (1.0 - behind);
    float surf = (1.0 - smoothstep(breakAt * 0.7, breakAt + 0.5, field.distance)) * max(bore, lace * 0.7 * thickness) * mix(0.75, 1.0, state);
    float foam = max(max(caps, breaking * nearShore), surf * nearShore) * min(1.0, swell.sea * 1.5);
    swell.foam = lite_wave_foam_cover(foam, dither, swell.fine);
    return swell;
}

// The swell's light on the water: banded crests a little brighter and greener (light through their
// thin tops), troughs a little darker, and foam. The foam goes on first and the wave's light over it,
// so foam (the surf's, and the edge foam drawn before this) rides the wave as one surface: a crest
// passing through it lifts and tints it like the water around it. A faint grain on the texel grid
// keeps the bands and the foam from reading as flat colour.
vec3 lite_ocean_swell_colour(vec3 seen, LiteSwell swell, vec3 light, vec3 surfacePosition) {
    vec2 anchored = lite_water_anchored(surfacePosition);
    float dither = lite_wave_dither(ivec2(floor(anchored * 16.0)));
    float grain = lite_wave_grain(anchored, swell.fine);
    seen = mix(seen, vec3(0.90, 0.95, 1.0) * light * grain, swell.foam * 0.85);
    float bands = mix(swell.crest, floor(swell.crest * 4.0 + dither) / 4.0, swell.fine);
    float state = LiteWaveInfo.x;
    seen *= 1.0 + (bands - 0.35) * (0.16 + 0.1 * state) * swell.sea;
    seen = mix(seen, seen * vec3(0.92, 1.1, 1.06), bands * 0.35 * swell.sea);
    return seen * mix(1.0, grain, 0.4 * swell.sea);
}

// The sea's colour for the swash's sheet of water, as the water itself is drawn under each Water
// colours palette (lite_water_body, for the water texture's average texel), from the biome's water
// tint near the camera (LiteWaveWind.w, packed 0xRRGGBB). hue is the water's hue, for the beach seen
// through it.
vec3 lite_swash_water(vec3 light, out vec3 hue) {
    int packed = int(LiteWaveWind.w + 0.5);
    vec3 tint = vec3(float((packed >> 16) & 255), float((packed >> 8) & 255), float(packed & 255)) / 255.0;
    vec3 mean = LiteWaterSpriteMeans[0].rgb;
    hue = tint;
    if (lite_water(LITE_WATER_REALISTIC) || lite_water(LITE_WATER_ENHANCED)) {
        hue = lite_water(LITE_WATER_BIOME_DEPTH) ? mix(tint, LITE_REALISTIC_WATER, 0.3) : LITE_REALISTIC_WATER;
        return hue * light * 0.6;
    }
    return mean * tint * light;
}

// The swash's timing (fog098). The surf's foam leads its crest by about this share of a wave (the
// whitened band around the breaking crest), so the sheet starts up the sand as that leading edge
// reaches the waterline instead of after the crest itself has: before, it read as delayed.
const float LITE_SWASH_LEAD = 0.15;
// The share of a wave the sheet takes to run up. Short enough that its front leaves the waterline at
// about the foam's own speed, carrying the foam on up the beach, then slows and slides back.
const float LITE_SWASH_RUN_UP = 0.22;

// The swash: a thin sheet of water and foam that runs up the beach with each wave's leading foam and
// slides back after it, on the top faces of the beach blocks at the water's level (sand, gravel,
// stone, anything).
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
    // Each wave's crest reaches the waterline when the shore phase there passes a whole wave; its
    // leading foam a little before (u = 0).
    float waves = (LiteWaveInfo.y + lite_wave_along_shore(anchored)) / LITE_WAVE_TAU + LITE_SWASH_LEAD;
    float wave = floor(waves);
    float u = waves - wave;
    // Some waves run further than others, and further on some stretches of the beach. Shore run-up
    // (LiteWaveInfo.w, 1 to 2) lengthens the run in fair weather, up to a storm's and no further.
    float runUp = min(mix(0.8, 2.6, state) * LiteWaveInfo.w, 2.6);
    float reach = sea * runUp * (0.6 + 0.4 * lite_water_hash(ivec2(int(wave) & 1023, 77)))
        * (0.8 + 0.4 * lite_water_value_noise(anchored * 0.5));
    // The front runs up quickly, slows, and slides back down more slowly.
    float front = u < LITE_SWASH_RUN_UP ? reach * sin(1.5708 * u / LITE_SWASH_RUN_UP)
        : reach * (1.0 - smoothstep(LITE_SWASH_RUN_UP, 0.95, u));
    float ragged = (lite_water_value_noise(anchored * 4.0 + vec2(wave * 3.0, 0.0)) - 0.5) * 0.3 * min(front, 1.0);
    float edge = front + ragged - run;
    float covered = step(0.0, edge) * step(0.02, front);
    float draining = smoothstep(LITE_SWASH_RUN_UP, 0.95, u);

    // How bright the light here is, from the shaded colour over the texture, for the foam.
    vec3 texel = textureLod(LiteWaterAtlas, surface.uv, 0.0).rgb;
    float light = dot(color.rgb, vec3(0.299, 0.587, 0.114)) / max(dot(texel, vec3(0.299, 0.587, 0.114)), 0.04);

    // Wet sand: dark where this wave has been until it drains, and always a little at the waterline.
    float highWater = u < LITE_SWASH_RUN_UP ? front : reach;
    float wet = step(run, highWater) * (1.0 - smoothstep(LITE_SWASH_RUN_UP, 1.0, u));
    float damp = 1.0 - smoothstep(0.0, 0.5 + 0.5 * state, run);
    vec3 ground = color.rgb * (1.0 - 0.25 * max(wet, damp * 0.6) * min(1.0, sea * 1.5));
    // The sheet: the beach seen through a little of the sea's own water (its colour under the
    // chosen Water colours, with the biome's tint), catching the sky, clearer as it drains.
    float sheet = covered * mix(0.6, 0.3, draining) * min(1.0, sea * 2.0);
    vec3 hue;
    // A sheet a few pixels deep is lit right through, so it shows the water's colour brighter than
    // the sea's body seen from above: about as bright as the fixed blue-green it replaced.
    vec3 seaWater = lite_swash_water(vec3(light), hue) * 1.8;
    vec3 filtered = mix(vec3(1.0), hue / max(max(hue.r, hue.g), max(hue.b, 0.05)), 0.45) * 0.95;
    vec3 thinWater = mix(ground * filtered, seaWater + LiteWaterSkyColor.rgb * 0.2 * light, 0.4);
    ground = mix(ground, thinWater, sheet);
    // Foam on the running front, and lace on the sheet while it runs up. The front keeps at least a
    // texel's width however thin the foam is set, so it never breaks up into dots.
    float thickness = lite_wave_foam_thickness();
    float frontWidth = max((0.18 + 0.1 * state) * thickness, 0.07);
    float frontFoam = covered * (1.0 - smoothstep(0.0, frontWidth, edge)) * (u < 0.55 ? 1.0 : 0.4);
    float laceFoam = covered * smoothstep(0.55, 0.8, lite_water_value_noise(anchored * 2.0 + vec2(0.0, wave * 5.0))) * (1.0 - draining) * 0.8 * thickness;
    float foam = max(frontFoam, laceFoam) * min(1.0, sea * 1.5);
    float fine = lite_wave_fine(lite_wave_footprint(position));
    float foamTexel = lite_wave_foam_cover(foam, lite_wave_dither(ivec2(floor(anchored * 16.0))), fine);
    color.rgb = mix(ground, vec3(0.90, 0.95, 1.0) * light * lite_wave_grain(anchored, fine), foamTexel * 0.9);
}

#endif
