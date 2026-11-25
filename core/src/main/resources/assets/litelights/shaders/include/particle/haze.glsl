#ifndef LITELIGHTS_PARTICLE_HAZE_GLSL
#define LITELIGHTS_PARTICLE_HAZE_GLSL

// What the haze puffs (rain and snow mist, dust clouds, Nether ash haze, End mist) share between
// the 3D puffs (particle/puff.*), the classic billboards (particle/soft.* with LITE_HAZE) and the
// Mist field (post/mist_field.fsh): the drifting world-space noise that breaks them up, and each
// kind's shape. DECISIONS fog097 has the reasoning.

#include <litelights:frame.glsl>

// The kinds, in ParticleKind.Haze's order after NONE.
const int LITE_HAZE_MIST = 0;
const int LITE_HAZE_SAND = 1;
const int LITE_HAZE_ASH = 2;
const int LITE_HAZE_END = 3;

// Each kind's ellipsoid as multiples of the particle's size: along the wind, across it, and up.
// Mist and End mist hang low and flat, dust clouds stretch out along the wind, ash haze is rounder.
// The billboard drew a soft disc about 0.45 of its size across, so these are wider than that and a
// little thinner, and the core optical depths below bring the air each puff fills back to what the
// billboard covered.
vec3 lite_haze_radii(int kind) {
    if (kind == LITE_HAZE_SAND) {
        return vec3(1.15, 0.75, 0.45);
    }
    if (kind == LITE_HAZE_ASH) {
        return vec3(0.75, 0.75, 0.6);
    }
    if (kind == LITE_HAZE_END) {
        return vec3(0.85, 0.85, 0.42);
    }
    return vec3(0.8, 0.8, 0.5);
}

// Optical depth straight through the middle of a puff, before the noise thins it. Set by eye against
// the classic billboards in the same storms (fog097-shots), so each kind fills about as much air.
float lite_haze_core_depth(int kind) {
    if (kind == LITE_HAZE_SAND) {
        return 4.5;
    }
    if (kind == LITE_HAZE_ASH) {
        return 3.5;
    }
    if (kind == LITE_HAZE_END) {
        return 6.0;
    }
    return 11.0;
}

// Within this many blocks of the eye a puff thins out (ParticleKind's nearFade for the billboards,
// which faded out by their centre over the same distance), so the air close by stays as clear as it
// was and a storm's many overlapping puffs don't close in around the camera.
float lite_haze_near(int kind) {
    if (kind == LITE_HAZE_SAND) {
        return 6.0;
    }
    if (kind == LITE_HAZE_ASH || kind == LITE_HAZE_END) {
        return 5.0;
    }
    return 7.0;
}

// The finer of the two noise scales that break up a puff, in blocks: about half its size, as a power
// of two from 1 to 8, so even the End's small puffs have wisps across them instead of a round outline.
float lite_haze_detail_scale(float size) {
    return exp2(clamp(floor(log2(max(size, 1.0) * 0.5) + 0.5), 0.0, 3.0));
}

// Noise positions are world positions wrapped every 2048 blocks, as the fog's wisps are, so they keep
// their precision far from the origin. Every noise scale below divides 2048, and the lattice wraps
// with it, so crossing a wrap line changes nothing.
const int LITE_HAZE_WRAP_MASK = 2047;

// World position (wrapped) of a camera-relative point, moved back along the haze's drift
// (LiteFeatureValuesB.xyz, the wind's travel integrated over game ticks and wrapped the same way), so
// the pattern rides the wind instead of sticking to the puffs or the screen.
vec3 lite_haze_world(vec3 cameraRelative) {
    return vec3(LiteCameraBlock.xyz & LITE_HAZE_WRAP_MASK) + lite_block_local(cameraRelative) - LiteFeatureValuesB.xyz;
}

// A hash of a lattice cell, 0..1. Cells wrap at cellMask + 1 (a power of two), so the pattern repeats
// exactly with the world positions. The wrapped cell goes through Dave Hoskins' float hash (the
// hash13 form of lite_hash33, under its notice in noise.glsl): float multiplies run at full rate
// where 32-bit integer ones don't, and the integer version made the haze shaders cost about three
// times as much (fog097-bench).
float lite_haze_hash(ivec3 cell, int cellMask) {
    vec3 p = fract(vec3(cell & ivec3(cellMask)) * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

// Smooth 3D value noise, 0..1, on cells of `scale` blocks (a power of two from 1 to 64).
float lite_haze_noise(vec3 world, float scale) {
    vec3 p = world / scale;
    vec3 cellFloor = floor(p);
    vec3 f = p - cellFloor;
    vec3 blend = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    ivec3 cell = ivec3(cellFloor);
    int cellMask = int(2048.0 / scale) - 1;
    float n000 = lite_haze_hash(cell, cellMask);
    float n100 = lite_haze_hash(cell + ivec3(1, 0, 0), cellMask);
    float n010 = lite_haze_hash(cell + ivec3(0, 1, 0), cellMask);
    float n110 = lite_haze_hash(cell + ivec3(1, 1, 0), cellMask);
    float n001 = lite_haze_hash(cell + ivec3(0, 0, 1), cellMask);
    float n101 = lite_haze_hash(cell + ivec3(1, 0, 1), cellMask);
    float n011 = lite_haze_hash(cell + ivec3(0, 1, 1), cellMask);
    float n111 = lite_haze_hash(cell + ivec3(1, 1, 1), cellMask);
    float bottom = mix(mix(n000, n100, blend.x), mix(n010, n110, blend.x), blend.y);
    float top = mix(mix(n001, n101, blend.x), mix(n011, n111, blend.x), blend.y);
    return mix(bottom, top, blend.z);
}

// Clumps and gaps: a slow 32-block noise that makes some stretches of air thick and others nearly
// clear, so the haze isn't one even sheet. 0.45 to 1.55, averaging about 1.
float lite_haze_clumps(vec3 world) {
    return mix(0.45, 1.55, smoothstep(0.15, 0.85, lite_haze_noise(world, 32.0)));
}

// The wind's heading as a horizontal unit vector (x its cosine, z its sine, as Wind.heading).
vec3 lite_haze_wind_axis() {
    return vec3(cos(LiteFeatureValuesD.y), 0.0, sin(LiteFeatureValuesD.y));
}

#endif
