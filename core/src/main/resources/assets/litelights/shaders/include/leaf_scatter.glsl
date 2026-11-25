#ifndef LITELIGHTS_LEAF_SCATTER_GLSL
#define LITELIGHTS_LEAF_SCATTER_GLSL

#include <litelights:water/common.glsl>

// Small pixel-art leaves scattered over a surface, at most one to each half-block cell: the floating
// leaves on water (water/surface.glsl) and the leaves lying under trees (vegetation/ground_leaves.glsl)
// are the same scatter, so the two look alike. Each leaf is one of four 5 x 5 shapes, mirrored and
// turned by its cell's hash, a few texels across.
const int LITE_LEAF_SHAPES[4] = int[4](1293208, 8601062, 490944, 407744);

// anchored: the surface's position from lite_water_anchored. density: 0 to 1, the share of cells
// that hold a leaf is 0.45 times it. drift: 1 lets each leaf wander a texel or so around its spot
// with the time (on water), 0 keeps it still. Returns 1 on a leaf texel, 0 elsewhere; look is the
// leaf's own hash (bits 5 and 6 pick its colour) and texel the texel hit, for its shading.
float lite_leaf_scatter(vec2 anchored, float density, float time, float drift, out uint look, out ivec2 texel) {
    look = 0u;
    texel = ivec2(floor(anchored * 16.0));
    ivec2 cell = texel >> 3;
    if (lite_water_hash(cell + ivec2(517, 129)) > density * 0.45) {
        return 0.0;
    }
    vec2 seed = lite_water_hash2(cell + ivec2(31, 977));
    vec2 sway = vec2(sin(time * 0.23 + seed.x * 6.2831), cos(time * 0.19 + seed.y * 6.2831));
    ivec2 centre = (cell << 3) + ivec2(2) + ivec2(seed * 3.99) + ivec2(round(sway * drift));
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
    look = bits;
    return 1.0;
}

#endif
