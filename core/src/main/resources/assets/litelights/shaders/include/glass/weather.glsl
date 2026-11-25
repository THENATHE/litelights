#ifndef LITELIGHTS_GLASS_WEATHER_GLSL
#define LITELIGHTS_GLASS_WEATHER_GLSL

#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:water/uniforms.glsl>
#include <litelights:water/common.glsl>

// Weather on glass (idea by duffopuffo, Weather Glass; concepts only): frost, snow and sand that
// gather along the edges of each window, in bands that are wider on larger windows and fuller in the
// corners, with snow deepest along the bottom. Everything is drawn as pixels on a grid of 16 or 32 to
// a block, fixed to the world; there is no refraction or blur. Rain on glass is the wall streaks'
// particles (RainDetails), not drawn here.
//
// Values from com.thenathe.litelights.render.feature.weather.GlassWeather (the LiteFrame block):
//   LiteGlassCold    x frost 0..1, y snow 0..1, z dust 0..1, w 1 when the dust is red sand
//   LiteGlassInfo    x texels per block (0: nothing to draw); y, z, w unused
// and from the water stream's LiteWater block, LiteGlassSprites: the block atlas places of the snow,
// sand and red sand textures, which snow and sand are drawn in.
//
// Two maps the water stream keeps in LiteFluidData are read: the rain tops under its column map
// (RainTopGrid), for which side of the glass is outdoors (frost, snow and sand form only there),
// and under those the glass windows (GlassWindowGrid), for how far each glass block lies from its
// window's edges.

const int LITE_GLASS_NO_TOP = -2147483647;
const vec3 LITE_GLASS_FROST = vec3(0.86, 0.91, 0.96);
// Snow lies a shade cooler than the snow block, so the odd bright texel reads as a sparkle, and
// bluer toward its inner edge, where it is thin over the glass.
const vec3 LITE_GLASS_SNOW_SHADE = vec3(0.93, 0.95, 0.98);
const vec3 LITE_GLASS_SNOW_INNER = vec3(0.84, 0.89, 0.97);
// Vanilla's own snow, sand and red sand colours (their block textures' main tones), for when the
// block atlas's sprites aren't known.
const vec3 LITE_GLASS_SNOW_TONES[3] = vec3[3](vec3(1.0), vec3(0.969, 0.996, 0.996), vec3(0.941, 0.992, 0.992));
const vec3 LITE_GLASS_SAND_TONES[4] = vec3[4](vec3(0.855, 0.812, 0.639), vec3(0.835, 0.769, 0.588),
    vec3(0.89, 0.859, 0.69), vec3(0.82, 0.729, 0.541));
const vec3 LITE_GLASS_RED_SAND_TONES[4] = vec3[4](vec3(0.749, 0.404, 0.129), vec3(0.698, 0.376, 0.122),
    vec3(0.796, 0.431, 0.141), vec3(0.675, 0.341, 0.071));

bool lite_glass_active() {
    return LiteGlassInfo.x > 0.0;
}

// --- Hashing ---

uint lite_glass_hash_bits(ivec3 p) {
    uint h = uint(p.x) * 0x8da6b343u ^ uint(p.y) * 0xd8163841u ^ uint(p.z) * 0xcb1ab31fu ^ 0x9e3779b9u;
    h ^= h >> 15;
    h *= 0x2c1b3c6du;
    h ^= h >> 12;
    h *= 0x297a2d39u;
    h ^= h >> 15;
    return h;
}

float lite_glass_unit(uint bits) {
    return float(bits & 0xFFFFFFu) / 16777216.0;
}

float lite_glass_hash(ivec3 p) {
    return lite_glass_unit(lite_glass_hash_bits(p));
}

// --- Where rain reaches ---

// The world height rain stops at in a column, or LITE_GLASS_NO_TOP where the map doesn't know it.
int lite_glass_rain_top(ivec2 column) {
    if (!lite_fluid_map_contains(column)) {
        return LITE_GLASS_NO_TOP;
    }
    ivec4 texel = ivec4(texelFetch(LiteFluidData, (column & 255) + ivec2(0, 256), 0) * 255.0 + 0.5);
    int packed = texel.r + texel.g * 256;
    return packed == 0 ? LITE_GLASS_NO_TOP : packed - 1 + LiteWaterFlags.w;
}

// Whether rain falls through this block of air: it is at or above where rain stops in its column.
bool lite_glass_open(ivec3 cell) {
    int top = lite_glass_rain_top(cell.xz);
    return top != LITE_GLASS_NO_TOP && cell.y >= top;
}

// Something rain can't pass sits right under this block of air (the ground, a sill), so snow can
// pile against the glass above it.
bool lite_glass_on_ledge(ivec3 front) {
    return lite_glass_rain_top(front.xz) == front.y;
}

// The block in front of the face lies under a water or lava surface (the column map's), so the glass
// has fluid against it rather than air and can't frost.
bool lite_glass_under_fluid(ivec3 front) {
    LiteFluidColumn column = lite_fluid_column(front.xz);
    if (!column.present) {
        return false;
    }
    float surface = column.surfaceY + float(LiteCameraBlock.y) + LiteCameraFraction.y;
    return float(front.y) + 0.5 < surface;
}

ivec3 lite_glass_world_block(vec3 blockLocal) {
    return LiteCameraBlock.xyz + ivec3(floor(blockLocal));
}

// --- The face's grid ---

// A face's pixel grid: texel in world texels (u across, v up; on flat faces x and z), the texel's
// place inside its block, which plane it lies on, and its blocks.
struct LiteGlassFace {
    ivec2 texel;
    vec2 inBlock;     // 0..1 across the block along u and v
    int plane;        // the face's plane in sixteenths of a block along its normal, for the hashes
    bool horizontal;  // a top face (snow and sand lie all over it)
    int axis;         // the axis the face looks along: 0 x, 1 y, 2 z (GlassWindowGrid's)
    ivec3 outward;    // unit normal pointing away from the glass, into the air
    ivec3 glass;      // the block of glass the face belongs to
    ivec3 front;      // the block of air the face looks into
    float texels;     // texels per block
};

LiteGlassFace lite_glass_face(vec3 blockLocal, ivec3 outward, float texels) {
    LiteGlassFace face;
    face.outward = outward;
    face.texels = texels;
    face.horizontal = outward.y != 0;
    vec2 plane2;
    float along;
    ivec2 cameraBlock;
    int cameraAlong;
    if (outward.y != 0) {
        face.axis = 1;
        plane2 = blockLocal.xz;
        cameraBlock = LiteCameraBlock.xz;
        along = blockLocal.y;
        cameraAlong = LiteCameraBlock.y;
    } else if (outward.x != 0) {
        face.axis = 0;
        plane2 = blockLocal.zy;
        cameraBlock = LiteCameraBlock.zy;
        along = blockLocal.x;
        cameraAlong = LiteCameraBlock.x;
    } else {
        face.axis = 2;
        plane2 = blockLocal.xy;
        cameraBlock = LiteCameraBlock.xy;
        along = blockLocal.z;
        cameraAlong = LiteCameraBlock.z;
    }
    // A hair inside the face, so a texel on a block's edge belongs to the face it is drawn on.
    vec3 inside = blockLocal - vec3(outward) * (1.0 / 256.0);
    face.glass = lite_glass_world_block(inside);
    face.front = lite_glass_world_block(inside + vec3(outward));
    vec2 scaled = floor(plane2 * texels);
    face.texel = ivec2(scaled) + cameraBlock * int(texels);
    face.inBlock = fract((scaled + 0.5) / texels);
    face.plane = cameraAlong * 16 + int(floor(along * 16.0 + 0.5)) + (outward.x + outward.y + outward.z) * 7919;
    return face;
}

// --- Which side of the glass is outdoors ---

// How many blocks out from the glass rain first falls past it: 0 when the block of air in front of
// the face is open, up to LITE_GLASS_SIDE_REACH when it is only that far out (an eave, an awning, a
// porch roof), or LITE_GLASS_SIDE_REACH + 1 when it isn't within reach (a room).
const int LITE_GLASS_SIDE_REACH = 3;

int lite_glass_open_distance(ivec3 front, ivec3 outward) {
    for (int step = 0; step <= LITE_GLASS_SIDE_REACH; step++) {
        if (lite_glass_open(front + outward * step)) {
            return step;
        }
    }
    return LITE_GLASS_SIDE_REACH + 1;
}

// How much frost, snow and sand a face gets. A side of the glass is outdoors when rain falls within a few
// blocks of it and no closer on the other side: under an overhang or a porch the open sky is a block
// or three out, while inside a room the rain-covered air goes on until a wall, and the outdoors
// beyond that wall (when the room is small) is further than the outside of the window is. So the
// inside of a window gets none, and free-standing glass, open on both sides, gets them on both. 1 open
// to the sky, less the further out the sky is; faces looking down get none.
float lite_glass_exposure(LiteGlassFace face) {
    if (face.outward.y < 0) {
        return 0.0;
    }
    if (face.outward.y > 0) {
        return lite_glass_open(face.front) ? 1.0 : 0.0;
    }
    int near = lite_glass_open_distance(face.front, face.outward);
    if (near == 0) {
        return 1.0;
    }
    if (near > LITE_GLASS_SIDE_REACH) {
        return 0.0;
    }
    // The block of air on the glass's other side: full blocks and panes alike, the glass's own block
    // lies between the two.
    int far = lite_glass_open_distance(face.glass - face.outward, -face.outward);
    if (far < near) {
        return 0.0;
    }
    return near == 1 ? 0.5 : near == 2 ? 0.35 : 0.25;
}

// --- The window a face belongs to ---

// The glass window table (GlassWindowGrid): under the rain tops, two texels to an entry (its key,
// then its data), 128 entries to a row.
const int LITE_GLASS_WINDOW_Y = 512;
const int LITE_GLASS_WINDOW_SLOTS = 16384;
const int LITE_GLASS_WINDOW_PROBES = 8;

struct LiteGlassWindow {
    bool known;   // the block has an entry (glass beyond the map's reach, or a pane's top, has none)
    vec4 runs;    // blocks of the same window beyond this one toward u low, u high, v low and v high
    float size;   // the window's smaller side, in blocks
};

uint lite_glass_window_bits(int slot, int part) {
    ivec2 at = ivec2((slot & 127) * 2 + part, LITE_GLASS_WINDOW_Y + (slot >> 7));
    uvec4 bytes = uvec4(texelFetch(LiteFluidData, at, 0) * 255.0 + 0.5);
    return bytes.r | bytes.g << 8u | bytes.b << 16u | bytes.a << 24u;
}

LiteGlassWindow lite_glass_window(LiteGlassFace face) {
    LiteGlassWindow window;
    window.known = false;
    window.runs = vec4(0.0);
    window.size = 1.0;
    if (!lite_fluid_map_contains(face.glass.xz)) {
        return window;
    }
    uint key = 1u | uint(face.axis) << 1u | uint(face.glass.x & 255) << 3u | uint(face.glass.z & 255) << 11u
        | uint((face.glass.y - LiteWaterFlags.w) & 4095) << 19u;
    int slot = int((key * 0x9E3779B1u) >> 18u);
    for (int probe = 0; probe < LITE_GLASS_WINDOW_PROBES; probe++) {
        uint stored = lite_glass_window_bits(slot, 0);
        if (stored == key) {
            uint data = lite_glass_window_bits(slot, 1);
            window.known = true;
            window.runs = vec4(uvec4(data, data >> 6u, data >> 12u, data >> 18u) & 63u);
            window.size = float(data >> 24u);
            break;
        }
        if (stored == 0u) {
            break;
        }
        slot = (slot + 1) & (LITE_GLASS_WINDOW_SLOTS - 1);
    }
    return window;
}

// --- Bands along the window's edges ---

// How wide, in blocks, a full band is on a window whose smaller side is `size` blocks: a couple of
// texels on a single block, wider on larger windows, at most 0.6 of a block (about ten texels at 16x)
// from eight blocks up, so it never fills a window.
float lite_glass_band_width(float size) {
    return min(0.6, 0.05 + 0.07 * size);
}

// How deep a texel sits in its window from the nearest edge, in blocks. Along a straight edge it is
// the distance to that edge; near a corner a smooth minimum of the distances across and up makes it
// less, so a band drawn where this is under its width rounds into each corner and fills it in, up
// to 1.6 times its width along the diagonal. bottom > 1 counts the bottom edge as that much nearer,
// so the band is that much deeper there.
float lite_glass_edge_depth(LiteGlassFace face, LiteGlassWindow window, float band, float bottom) {
    float acrossU = min(window.runs.x + face.inBlock.x, window.runs.y + 1.0 - face.inBlock.x);
    float acrossV = min((window.runs.z + face.inBlock.y) / bottom, window.runs.w + 1.0 - face.inBlock.y);
    float rounding = band * 2.5;
    float blend = max(rounding - abs(acrossU - acrossV), 0.0) / rounding;
    return min(acrossU, acrossV) - blend * blend * rounding * 0.25;
}

// How far inside a band of `band` blocks along the window's edges the texel lies, in texels, with a
// ragged inner edge (noise fixed per texel, about a third either way): above 0 inside the band,
// 0 or below past its inner edge (-1 a texel beyond it), and -1000 where there is no band.
float lite_glass_band_texels(LiteGlassFace face, LiteGlassWindow window, float band, float bottom, int salt) {
    if (band <= 0.0 || !window.known) {
        return -1000.0;
    }
    float depth = lite_glass_edge_depth(face, window, band, bottom);
    float ragged = 0.65 + 0.7 * lite_water_value_noise(vec2(face.texel) * 0.27 + float(face.plane ^ salt) * 0.071);
    return (band * ragged - depth) * face.texels;
}

// How many texels in from a band's inner edge a texel lies, from lite_glass_band_texels: 0 outside
// it, 1 the innermost, 2 the next, 3 for anything deeper.
int lite_glass_band_step(float inside) {
    return inside > 0.0 ? min(3, 1 + int(inside)) : 0;
}

int lite_glass_band(LiteGlassFace face, LiteGlassWindow window, float band, float bottom, int salt) {
    return lite_glass_band_step(lite_glass_band_texels(face, window, band, bottom, salt));
}

// --- Frost, snow and sand ---

// Frost and sand lie in thinner bands than snow, these shares of its width.
const float LITE_GLASS_FROST_BAND = 0.78;
const float LITE_GLASS_SAND_BAND = 0.68;

// Whether a texel lies on frost's crystal lattice: diagonal lines four texels apart, one way, the
// other or both in each 4 x 4 cell of the grid, so the lines branch and turn along the edge.
bool lite_glass_frost_lattice(LiteGlassFace face) {
    uint way = lite_glass_hash_bits(ivec3(face.texel >> 2, face.plane ^ 0x7a13)) % 3u;
    bool rising = ((face.texel.x - face.texel.y) & 3) == 0;
    bool falling = ((face.texel.x + face.texel.y) & 3) == 0;
    return (way != 1u && rising) || (way != 0u && falling);
}

// Frost grows in from the window's edges and fills its corners first, on the outdoor side of the
// glass, as snow and sand do: thinner under an eave or a porch, none on the inside of a room. Sky
// light reaches through water, but a face with water or lava against it can't frost.
//
// It is drawn as crystals on the face's grid: slightly uneven texels, the odd brighter crystal, a
// fainter inner edge where the lattice is missing, and feathers, short runs of the lattice growing on
// past the inner edge toward the middle. The body's coverage stays at 0.66 or more, so even seen
// through the glass (0.9 times) it is above the cut-out's highest threshold (0.58) and clear glass
// shows it whole; only the feathers and the inner edge, at 0.56 to 0.6, thin out there.
vec4 lite_glass_frost(LiteGlassFace face, LiteGlassWindow window, float exposure) {
    float frost = LiteGlassCold.x * exposure;
    if (frost <= 0.0 || lite_glass_under_fluid(face.front)) {
        return vec4(0.0);
    }
    float band = frost * lite_glass_band_width(window.size) * LITE_GLASS_FROST_BAND;
    float inside = lite_glass_band_texels(face, window, band, 1.0, 0x44f1);
    int reached = lite_glass_band_step(inside);
    vec3 crystal = vec3(0.95, 0.97, 1.0);
    if (reached == 0) {
        // Feathers only once the band is a few texels wide, longer along some stretches of the edge.
        float featherReach = min(2.5, band * face.texels * 0.35)
            * lite_water_value_noise(vec2(face.texel) * 0.19 + float(face.plane) * 0.053);
        if (-inside >= featherReach || !lite_glass_frost_lattice(face)) {
            return vec4(0.0);
        }
        return vec4(mix(LITE_GLASS_FROST, crystal, 0.3), 0.58);
    }
    uint bits = lite_glass_hash_bits(ivec3(face.texel, face.plane ^ 0x44f1));
    if (reached == 1) {
        return lite_glass_frost_lattice(face) ? vec4(mix(LITE_GLASS_FROST, crystal, 0.3), 0.7) : vec4(LITE_GLASS_FROST, 0.56);
    }
    if (lite_glass_unit(bits) < 0.045) {
        return vec4(crystal, 0.82);
    }
    // Thicker toward the frame: a little whiter in the deepest texels.
    vec3 rime = mix(LITE_GLASS_FROST, crystal, reached == 3 ? 0.3 : 0.1) * (0.97 + 0.06 * lite_glass_unit(bits >> 8u));
    return vec4(rime, 0.66 + 0.12 * lite_glass_unit(bits >> 16u));
}

// How many times deeper than its sides snow lies along a window's bottom: twice, and three times
// where the ground or a ledge lies under the window (a pile). It never reaches more than a block up,
// nor more than a fifth of the window's height, so a small window isn't buried.
float lite_glass_snow_bottom(float band, float height, bool ledge) {
    float deepest = min(1.0, 0.2 * height);
    return max(1.0, min(ledge ? 3.0 : 2.0, deepest / max(band, 0.001)));
}

// The colour of a block texture under a texel of the face's grid: the texture laid over each block
// face, one of its texels to each texel of the grid at the texture's own size (every other one of a
// 32x pack's at 16x, each twice at 32x on a 16x pack), so it holds still on the glass. The sprite is
// read from the block atlas (LiteGlassSprites), so a resource pack's snow and sand carry through;
// while the atlas places aren't known, a tone of vanilla's is picked per texel instead.
vec3 lite_glass_sprite_texel(vec4 sprite, LiteGlassFace face, vec3 fallback) {
    if (sprite.x > 1.5) {
        return fallback;
    }
    vec2 atlasSize = vec2(textureSize(LiteWaterAtlas, 0));
    ivec2 origin = ivec2(sprite.xy * atlasSize + 0.5);
    ivec2 size = max(ivec2((sprite.zw - sprite.xy) * atlasSize + 0.5), ivec2(1));
    ivec2 cell = ivec2(floor(vec2(face.texel) * vec2(size) / face.texels));
    ivec2 wrapped = ((cell % size) + size) % size;
    // The grid counts up the face, the texture down.
    ivec2 inSprite = ivec2(wrapped.x, size.y - 1 - wrapped.y);
    return texelFetch(LiteWaterAtlas, origin + inSprite, 0).rgb;
}

vec3 lite_glass_snow_texel(LiteGlassFace face, int fromInnerEdge) {
    uint bits = lite_glass_hash_bits(ivec3(face.texel, face.plane ^ 0x51ee));
    // About one texel in thirty catches the light, away from the thin inner edge.
    if (fromInnerEdge != 1 && lite_glass_unit(bits) < 0.035) {
        return vec3(1.0);
    }
    vec3 snow = lite_glass_sprite_texel(LiteGlassSprites[0], face, LITE_GLASS_SNOW_TONES[(bits >> 24u) % 3u]);
    vec3 shade = fromInnerEdge == 1 ? LITE_GLASS_SNOW_INNER
        : fromInnerEdge == 2 ? mix(LITE_GLASS_SNOW_SHADE, LITE_GLASS_SNOW_INNER, 0.4) : LITE_GLASS_SNOW_SHADE;
    return snow * shade;
}

vec3 lite_glass_sand_texel(LiteGlassFace face, int fromInnerEdge) {
    uint bits = lite_glass_hash_bits(ivec3(face.texel, face.plane ^ 0x3e95));
    uint tone = bits % 4u;
    vec3 sand = LiteGlassCold.w > 0.5
        ? lite_glass_sprite_texel(LiteGlassSprites[2], face, LITE_GLASS_RED_SAND_TONES[tone])
        : lite_glass_sprite_texel(LiteGlassSprites[1], face, LITE_GLASS_SAND_TONES[tone]);
    // Grains a little lighter or darker than the texture, and about one in ten a dark one, so the
    // pale sand reads as grains over the glass.
    float grain = lite_glass_unit(bits >> 8u) < 0.1 ? 0.76 : 0.88 + 0.2 * lite_glass_unit(bits >> 4u);
    // A slightly darker inner edge, where the sand thins out over the glass.
    return sand * grain * (fromInnerEdge == 1 ? 0.85 : fromInnerEdge == 2 ? 0.93 : 1.0);
}

// Snow stuck along the outside of the window's edges, deepest along the bottom, in the snow block's
// own pixels. Flat tops fill in.
vec4 lite_glass_snow(LiteGlassFace face, LiteGlassWindow window, float exposure) {
    float snow = LiteGlassCold.y;
    if (snow <= 0.0 || exposure <= 0.0) {
        return vec4(0.0);
    }
    if (face.horizontal) {
        float roll = lite_glass_hash(ivec3(face.texel, face.plane ^ 0x1d87));
        return roll < snow * 0.85 ? vec4(lite_glass_snow_texel(face, 3), 0.95) : vec4(0.0);
    }
    float band = snow * lite_glass_band_width(window.size) * exposure;
    // The air in front of the window's bottom row.
    ivec3 belowWindow = face.front - ivec3(0, int(window.runs.z), 0);
    float height = window.runs.z + window.runs.w + 1.0;
    float bottom = lite_glass_snow_bottom(band, height, lite_glass_on_ledge(belowWindow));
    int reached = lite_glass_band(face, window, band, bottom, 0x1d87);
    if (reached == 0) {
        return vec4(0.0);
    }
    return vec4(lite_glass_snow_texel(face, reached), 1.0);
}

// Sand settled after a sandstorm along the outside of the window's edges, a little deeper at the
// bottom, in the sand block's own pixels; specks all over flat tops.
vec4 lite_glass_dust(LiteGlassFace face, LiteGlassWindow window, float exposure) {
    float dust = LiteGlassCold.z;
    if (dust <= 0.0 || exposure <= 0.0) {
        return vec4(0.0);
    }
    if (face.horizontal) {
        float speck = lite_glass_hash(ivec3(face.texel, face.plane ^ 0x2b61));
        if (speck >= dust * exposure * 0.3) {
            return vec4(0.0);
        }
        return vec4(lite_glass_sand_texel(face, 3), 0.88);
    }
    float band = dust * lite_glass_band_width(window.size) * exposure * LITE_GLASS_SAND_BAND;
    float inside = lite_glass_band_texels(face, window, band, 1.4, 0x3e95);
    int reached = lite_glass_band_step(inside);
    // A broken, grainy inner edge: gaps among the innermost texels, loose grains up to a texel and a
    // half past it (fewer while the band is under a texel wide), and the odd gap further in.
    float keep = reached == 3 ? 0.96 : reached == 2 ? 0.86 : reached == 1 ? 0.62
        : inside > -1.5 ? 0.22 * min(1.0, band * face.texels) : 0.0;
    uint bits = lite_glass_hash_bits(ivec3(face.texel, face.plane ^ 0x6c3b));
    if (lite_glass_unit(bits) >= keep) {
        return vec4(0.0);
    }
    return vec4(lite_glass_sand_texel(face, max(reached, 1)), 0.8 + 0.12 * lite_glass_unit(bits >> 8u));
}

// --- Putting it together ---

// A layer of colour and coverage over another.
vec4 lite_glass_over(vec4 top, vec4 under) {
    float alpha = top.a + under.a * (1.0 - top.a);
    vec3 rgb = alpha > 0.0 ? (top.rgb * top.a + under.rgb * under.a * (1.0 - top.a)) / alpha : vec3(0.0);
    return vec4(rgb, alpha);
}

ivec3 lite_glass_axis(vec3 normal) {
    vec3 a = abs(normal);
    if (a.y >= a.x && a.y >= a.z) {
        return ivec3(0, normal.y > 0.0 ? 1 : -1, 0);
    }
    if (a.x >= a.z) {
        return ivec3(normal.x > 0.0 ? 1 : -1, 0, 0);
    }
    return ivec3(0, 0, normal.z > 0.0 ? 1 : -1);
}

// Snow over sand over frost on the outdoor side of a face.
vec4 lite_glass_outside(LiteGlassFace face, LiteGlassWindow window, float exposure) {
    vec4 landed = lite_glass_over(lite_glass_snow(face, window, exposure), lite_glass_dust(face, window, exposure));
    return lite_glass_over(landed, lite_glass_frost(face, window, exposure));
}

// The weather on this glass fragment: rgb and how much it covers. normal faces the camera; texelId
// names the fragment's texel of the grid, for anything that needs a fixed value per texel.
vec4 lite_glass_weather_layers(vec3 position, vec3 normal, vec2 lightCoord, out ivec3 texelId) {
    float texels = LiteGlassInfo.x;
    ivec3 outward = lite_glass_axis(normal);
    vec3 blockLocal = lite_block_local(position);
    LiteGlassFace near = lite_glass_face(blockLocal, outward, texels);
    texelId = ivec3(near.texel, near.plane ^ 0x0d2b);
    // Pixels of the grid smaller than a screen pixel would only shimmer: fade out with distance.
    float fadeStart = texels > 16.0 ? 28.0 : 40.0;
    float fade = 1.0 - smoothstep(fadeStart, fadeStart * 1.8, length(position));
    if (fade <= 0.0) {
        return vec4(0.0);
    }
    float skyLight = clamp(lightCoord.y / 240.0, 0.0, 1.0);

    vec4 layers = vec4(0.0);
    float exposure = lite_glass_exposure(near);
    if (exposure > 0.0) {
        layers = lite_glass_outside(near, lite_glass_window(near), exposure);
    } else if (skyLight > 0.0) {
        // Seen from inside: the outside of the glass is the far face, a block away for full blocks and
        // an eighth of a block for panes (whose faces lie inside the block). The terrain is drawn with
        // back faces culled, so that face isn't drawn from here: its frost, snow and sand are drawn on
        // this one where the line of sight meets it, so they sit on the outside and move against the
        // near face as the camera moves.
        float alongNormal = dot(blockLocal, vec3(abs(outward)));
        float offBoundary = abs(alongNormal - round(alongNormal));
        float thickness = offBoundary < 0.01 ? 1.0 : 0.125;
        vec3 view = normalize(position - lite_view_origin());
        float facing = dot(view, -vec3(outward));
        if (facing > 0.05) {
            vec3 farPoint = blockLocal + view * (thickness / facing);
            LiteGlassFace far = lite_glass_face(farPoint, -outward, texels);
            float farExposure = lite_glass_exposure(far);
            if (farExposure > 0.0) {
                layers = lite_glass_outside(far, lite_glass_window(far), farExposure);
                // A little fainter through the glass, but still above the cut-out's highest threshold
                // (0.58), so it stays whole on clear glass.
                layers.a *= 0.9;
            }
        }
    }
    layers.a *= fade;
    return layers;
}

#endif
