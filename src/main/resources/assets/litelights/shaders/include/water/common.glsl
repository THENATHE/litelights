#ifndef LITELIGHTS_WATER_COMMON_GLSL
#define LITELIGHTS_WATER_COMMON_GLSL

#include <litelights:frame.glsl>
#include <litelights:water/uniforms.glsl>

// Shared pieces of the water effects: hashing and noise, the column map, and positions that stay
// exact far from the world origin.

// The hue Realistic water takes when Biome and depth colour is off: a clear blue-green.
const vec3 LITE_REALISTIC_WATER = vec3(0.24, 0.52, 0.66);

// --- Positions ---

// Horizontal position for procedural patterns. The camera block is taken modulo 2048 on the CPU,
// so the value stays small and exact anywhere in the world. Patterns repeat every 2048 blocks;
// every frequency used below fits that period, so nothing jumps when the camera crosses it.
vec2 lite_water_anchored(vec3 cameraRelative) {
    return vec2(LiteWaterAnchor.xy) + lite_block_local(cameraRelative).xz;
}

ivec2 lite_water_world_column(vec3 cameraRelative) {
    return LiteCameraBlock.xz + ivec2(floor(lite_block_local(cameraRelative).xz));
}

// Snaps a horizontal surface position to the centre of its 1/16 texel, keeping the height.
vec3 lite_water_lock_horizontal(vec3 cameraRelative) {
    vec3 local = lite_block_local(cameraRelative);
    local.xz = (floor(local.xz * 16.0) + 0.5) / 16.0;
    return lite_camera_relative(local);
}

// Snaps a fluid surface position to the centre of its texel on a grid of texelsPerBlock, keeping
// the axis along the face's normal. Like lite_pixel_lock_face, but for textures that aren't 16 to a
// block (a high-resolution pack, Big Water's sprites squeezed onto another mod's fluid).
vec3 lite_water_lock(vec3 cameraRelative, vec3 axisNormal, float texelsPerBlock) {
    vec3 local = lite_block_local(cameraRelative);
    vec3 inside = local - axisNormal * (1.0 / 256.0);
    vec3 snapped = (floor(inside * texelsPerBlock) + 0.5) / texelsPerBlock;
    return lite_camera_relative(mix(snapped, local, step(vec3(0.5), abs(axisNormal))));
}

// How many texels of the drawn texture span a block on this fragment, from the screen derivatives
// of its texture coordinate against those of its position (the texture is laid on the face without
// stretch, so the ratio is the same along every direction), rounded to a power of two from 4 to 256.
float lite_water_texels_per_block(vec2 duvdx, vec2 duvdy, vec3 dpdx, vec3 dpdy, vec2 textureTexels) {
    float texels = length(duvdx * textureTexels) + length(duvdy * textureTexels);
    float blocks = length(dpdx) + length(dpdy);
    if (blocks < 1.0e-6 || texels < 1.0e-6) {
        return 16.0;
    }
    return exp2(clamp(round(log2(texels / blocks)), 2.0, 8.0));
}

// --- Hashing and noise ---

// Integer hash (xorshift-multiply). Cells are wrapped to 1024 so patterns stay periodic with the
// 2048-block anchor at every frequency used here.
uint lite_water_hash_bits(ivec2 cell) {
    uvec2 v = uvec2(cell & 1023);
    uint h = v.x * 0x8da6b343u ^ v.y * 0xd8163841u ^ 0x9e3779b9u;
    h ^= h >> 15;
    h *= 0x2c1b3c6du;
    h ^= h >> 12;
    h *= 0x297a2d39u;
    h ^= h >> 15;
    return h;
}

float lite_water_hash(ivec2 cell) {
    return float(lite_water_hash_bits(cell) & 0xFFFFFFu) / 16777216.0;
}

vec2 lite_water_hash2(ivec2 cell) {
    uint h = lite_water_hash_bits(cell);
    return vec2(float(h & 0xFFFFu), float(h >> 16)) / 65536.0;
}

float lite_water_value_noise(vec2 p) {
    ivec2 cell = ivec2(floor(p));
    vec2 f = fract(p);
    vec2 s = f * f * (3.0 - 2.0 * f);
    float a = lite_water_hash(cell);
    float b = lite_water_hash(cell + ivec2(1, 0));
    float c = lite_water_hash(cell + ivec2(0, 1));
    float d = lite_water_hash(cell + ivec2(1, 1));
    return mix(mix(a, b, s.x), mix(c, d, s.x), s.y);
}

// Distance between the nearest and second-nearest of a set of points that drift in circles around
// their cell centres: 0 on the edges between cells, larger inside them.
float lite_water_cell_edges(vec2 p, float time) {
    ivec2 cell = ivec2(floor(p));
    vec2 f = fract(p);
    float nearest = 8.0;
    float second = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 offset = ivec2(x, y);
            vec2 seed = lite_water_hash2(cell + offset);
            vec2 point = vec2(offset) + 0.5 + 0.38 * sin(time * (0.6 + seed) + seed * 6.2831);
            float d = length(point - f);
            if (d < nearest) {
                second = nearest;
                nearest = d;
            } else if (d < second) {
                second = d;
            }
        }
    }
    return second - nearest;
}

// --- The column map ---

struct LiteFluidColumn {
    bool present;     // inside the map, with a fluid surface
    bool lava;
    float surfaceY;   // camera-relative height of the fluid surface
    int shoreMask;    // which of the 8 neighbours end the fluid at this level (see lite_fluid_shore_distance)
    float leaves;     // 0..1, how much tree canopy hangs over the water nearby
    float landing;    // 0..1, how much water falls into this column from a neighbour
    float inMap;      // 1 well inside the map, falling to 0 over its last few columns, so the
                      // details the map drives (foam, leaves, caustics) fade out instead of
                      // stopping in a straight line where the map ends
};

bool lite_fluid_map_contains(ivec2 worldColumn) {
    ivec2 inMap = worldColumn - LiteWaterFlags.yz;
    return lite_water(LITE_WATER_COLUMN_MAP) && all(greaterThanEqual(inMap, ivec2(0))) && all(lessThan(inMap, ivec2(256)));
}

ivec4 lite_fluid_texel(ivec2 worldColumn) {
    return ivec4(texelFetch(LiteFluidData, worldColumn & 255, 0) * 255.0 + 0.5);
}

LiteFluidColumn lite_fluid_column(ivec2 worldColumn) {
    LiteFluidColumn column;
    column.present = false;
    column.lava = false;
    column.surfaceY = 0.0;
    column.shoreMask = 0;
    column.leaves = 0.0;
    column.landing = 0.0;
    column.inMap = 0.0;
    if (!lite_fluid_map_contains(worldColumn)) {
        return column;
    }
    ivec2 inMap = worldColumn - LiteWaterFlags.yz;
    int toEdge = min(min(inMap.x, inMap.y), min(255 - inMap.x, 255 - inMap.y));
    column.inMap = smoothstep(0.0, 12.0, float(toEdge));
    ivec4 texel = lite_fluid_texel(worldColumn);
    int height = texel.r | ((texel.g & 127) << 8);
    column.present = height != 0;
    column.lava = (texel.g & 128) != 0;
    column.surfaceY = float(LiteWaterFlags.w - LiteCameraBlock.y) + float(height) / 64.0 - LiteCameraFraction.y;
    column.shoreMask = texel.b;
    column.leaves = float(texel.a & 15) / 15.0;
    column.landing = float(texel.a >> 4) / 15.0;
    return column;
}

// Distance from a point in a column (f in 0..1 across the column) to the nearest neighbour that
// ends the fluid. Bits: 1 west, 2 east, 4 north, 8 south, then the corners NW, NE, SW, SE.
float lite_fluid_shore_distance(int mask, vec2 f) {
    float d = 2.0;
    if ((mask & 1) != 0) d = min(d, f.x);
    if ((mask & 2) != 0) d = min(d, 1.0 - f.x);
    if ((mask & 4) != 0) d = min(d, f.y);
    if ((mask & 8) != 0) d = min(d, 1.0 - f.y);
    if ((mask & 16) != 0) d = min(d, length(f));
    if ((mask & 32) != 0) d = min(d, length(vec2(1.0 - f.x, f.y)));
    if ((mask & 64) != 0) d = min(d, length(vec2(f.x, 1.0 - f.y)));
    if ((mask & 128) != 0) d = min(d, length(1.0 - f));
    return d;
}

// Falling-water landing strength, blended between column centres so the foam spreads softly.
float lite_fluid_landing(vec3 cameraRelative) {
    vec2 local = lite_block_local(cameraRelative).xz - 0.5;
    ivec2 base = LiteCameraBlock.xz + ivec2(floor(local));
    vec2 f = fract(local);
    float a = float(lite_fluid_texel(base).a >> 4);
    float b = float(lite_fluid_texel(base + ivec2(1, 0)).a >> 4);
    float c = float(lite_fluid_texel(base + ivec2(0, 1)).a >> 4);
    float d = float(lite_fluid_texel(base + ivec2(1, 1)).a >> 4);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y) / 15.0;
}

// The surface flow of a column (FluidFlowGrid), in blocks: 0 for still water and unknown columns.
// It sits in the blue and alpha of the rain tops' texels, under the column map.
vec2 lite_fluid_flow_at(ivec2 worldColumn) {
    ivec2 bytes = ivec2(texelFetch(LiteFluidData, (worldColumn & 255) + ivec2(0, 256), 0).ba * 255.0 + 0.5);
    bytes -= 256 * ivec2(greaterThan(bytes, ivec2(127)));
    return vec2(bytes) / 64.0;
}

// The flow at a point, blended between column centres like the landing strength, so it changes
// smoothly from block to block and slows toward the banks (land columns have none).
vec2 lite_fluid_flow(vec3 cameraRelative) {
    vec2 local = lite_block_local(cameraRelative).xz - 0.5;
    ivec2 base = LiteCameraBlock.xz + ivec2(floor(local));
    vec2 f = fract(local);
    vec2 a = lite_fluid_flow_at(base);
    vec2 b = lite_fluid_flow_at(base + ivec2(1, 0));
    vec2 c = lite_fluid_flow_at(base + ivec2(0, 1));
    vec2 d = lite_fluid_flow_at(base + ivec2(1, 1));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// --- The shore field (Ocean waves) ---

// What the ocean waves know about a point (ShoreField): how far it is from the waterline in blocks
// (positive on the water, negative up the beach), which way the shore lies, and how much ocean swell
// it gets (0 = none).
struct LiteShoreField {
    float distance;
    vec2 toShore;
    float strength;
};

// The shore field's first row in LiteFluidData, under the rain tops (256) and the glass windows (512 to
// 639): FluidDataTexture.SHORE_FIELD_Y.
const int LITE_SHORE_FIELD_Y = 640;

ivec4 lite_shore_texel(ivec2 worldColumn) {
    return ivec4(texelFetch(LiteFluidData, (worldColumn & 255) + ivec2(0, LITE_SHORE_FIELD_Y), 0) * 255.0 + 0.5);
}

vec4 lite_shore_values(ivec2 worldColumn) {
    ivec4 texel = lite_shore_texel(worldColumn);
    ivec2 toward = texel.gb - 256 * ivec2(greaterThan(texel.gb, ivec2(127)));
    return vec4(float(texel.r) / 4.0 - 8.0, vec2(toward) / 127.0, float(texel.a) / 255.0);
}

// Blended between column centres, so the waterline is where the distance crosses 0 and the waves'
// direction turns smoothly from block to block.
LiteShoreField lite_shore_field(vec3 cameraRelative) {
    vec2 local = lite_block_local(cameraRelative).xz - 0.5;
    ivec2 base = LiteCameraBlock.xz + ivec2(floor(local));
    vec2 f = fract(local);
    vec4 a = lite_shore_values(base);
    vec4 b = lite_shore_values(base + ivec2(1, 0));
    vec4 c = lite_shore_values(base + ivec2(0, 1));
    vec4 d = lite_shore_values(base + ivec2(1, 1));
    vec4 blended = mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
    LiteShoreField field;
    field.distance = blended.x;
    float length = dot(blended.yz, blended.yz);
    field.toShore = length > 1.0e-4 ? blended.yz * inversesqrt(length) : vec2(0.0);
    field.strength = blended.w;
    return field;
}

// --- Wakes ---

// What the wake field says about a point (WakeField): its height (0 for calm water, about -0.6 to
// 0.7 in a boat's wake), the surface's slope per block, the foam (0 to 1), and how far inside the
// field's window it lies (1 well inside, falling to 0 over its last six blocks).
struct LiteWake {
    float height;
    vec2 slope;
    float foam;
    float reach;
};

// The wake field's first row in LiteFluidData, under the shore field: FluidDataTexture.WAKE_FIELD_Y.
const int LITE_WAKE_FIELD_Y = 896;
// Byte steps per unit of height: WakeField.HEIGHT_STEPS.
const float LITE_WAKE_HEIGHT_STEPS = 96.0;
const float LITE_WAKE_CELLS_PER_BLOCK = 4.0;

LiteWake lite_no_wake() {
    return LiteWake(0.0, vec2(0.0), 0.0, 0.0);
}

// A cell's height and foam, blended from the last tick's to this tick's by the partial tick.
vec2 lite_wake_values(ivec2 worldCell) {
    vec4 texel = texelFetch(LiteFluidData, (worldCell & 255) + ivec2(0, LITE_WAKE_FIELD_Y), 0);
    vec2 heights = (texel.rg * 255.0 - 128.0) / LITE_WAKE_HEIGHT_STEPS;
    float t = LiteWakeParams.y;
    return vec2(mix(heights.y, heights.x, t), mix(texel.a, texel.b, t));
}

// Blended between cell centres, four cells to a block; the slope is the blend's own, so it changes
// from cell to cell like a faceted surface, which reads well on the pixel grid.
LiteWake lite_wake(vec3 cameraRelative) {
    vec2 local = lite_block_local(cameraRelative).xz * LITE_WAKE_CELLS_PER_BLOCK - 0.5;
    ivec2 base = ivec2(floor(local));
    ivec2 inWindow = base - LiteWakeWindow.xy;
    if (any(lessThan(inWindow, ivec2(0))) || any(greaterThan(inWindow, ivec2(254)))) {
        return lite_no_wake();
    }
    vec2 f = fract(local);
    ivec2 cell = LiteCameraBlock.xz * 4 + base;
    vec2 a = lite_wake_values(cell);
    vec2 b = lite_wake_values(cell + ivec2(1, 0));
    vec2 c = lite_wake_values(cell + ivec2(0, 1));
    vec2 d = lite_wake_values(cell + ivec2(1, 1));
    LiteWake wake;
    wake.height = mix(mix(a.x, b.x, f.x), mix(c.x, d.x, f.x), f.y);
    wake.slope = vec2(mix(b.x - a.x, d.x - c.x, f.y), mix(c.x - a.x, d.x - b.x, f.x)) * LITE_WAKE_CELLS_PER_BLOCK;
    wake.foam = mix(mix(a.y, b.y, f.x), mix(c.y, d.y, f.x), f.y);
    int toEdge = min(min(inWindow.x, inWindow.y), min(254 - inWindow.x, 254 - inWindow.y));
    wake.reach = smoothstep(0.0, 24.0, float(toEdge));
    return wake;
}

// --- Light ---

// Vanilla's default water colour (0x3F76E4), the tint the light estimate below is measured against.
const vec3 LITE_DEFAULT_WATER_TINT = vec3(0.247, 0.463, 0.894);

// How bright the light on a water fragment is, from its shaded colour divided by its texture
// (tint times light). Measured against the default water tint, so a darker biome tint reads as a
// little less light; plenty for foam and leaves, and it costs no lightmap lookup.
float lite_water_light_level(vec3 tintLight) {
    const vec3 LUMA = vec3(0.299, 0.587, 0.114);
    return dot(tintLight, LUMA) / dot(LITE_DEFAULT_WATER_TINT, LUMA);
}

#endif
