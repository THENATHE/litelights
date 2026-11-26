#ifndef LITELIGHTS_CLOUDS_VOLUME_GLSL
#define LITELIGHTS_CLOUDS_VOLUME_GLSL

// The volumetric Dynamic clouds' shared pieces (render.feature.clouds.CloudVolume): reading the
// cloud field's column maps (clouds.volume.CloudMap) and the noise atlas
// (clouds.volume.CloudNoiseVolume), and the soft shape a column's span of cloud is given. The
// numbers marked with a Java name must stay in step with it.

// CloudMap.HEIGHT_RANGE, SIZE, BASE_SOFTNESS and TOP_SOFTNESS.
const float LITE_CLOUD_HEIGHT_RANGE = 1600.0;
const float LITE_CLOUD_MAP_SIZE = 512.0;
const float LITE_CLOUD_BASE_SOFTNESS = 12.0;
const float LITE_CLOUD_TOP_SOFTNESS = 0.2;
// CloudNoiseVolume.PERIOD, TILE and TILES_ACROSS.
const float LITE_CLOUD_NOISE_PERIOD = 64.0;
const float LITE_CLOUD_NOISE_TILE = 66.0;
const int LITE_CLOUD_NOISE_TILES = 8;

// One band's span over a point (camera-relative x and z): r bottom and g top as shares of the
// height range, b density, a storminess; no cloud off the map. place is the map's corner,
// camera-relative, and its blocks per texel. The texel is held inside its band's tile, so
// filtering never reads the next.
vec4 lite_cloud_band(sampler2D map, vec3 place, vec2 xz, int band) {
    vec2 texel = (xz - place.xy) / place.z;
    if (any(lessThan(texel, vec2(0.0))) || any(greaterThan(texel, vec2(LITE_CLOUD_MAP_SIZE)))) {
        return vec4(0.0);
    }
    texel = clamp(texel, vec2(0.5), vec2(LITE_CLOUD_MAP_SIZE - 0.5));
    vec2 tile = vec2(float(band & 1), float(band >> 1));
    return textureLod(map, (tile * LITE_CLOUD_MAP_SIZE + texel) / (2.0 * LITE_CLOUD_MAP_SIZE), 0.0);
}

// The span's soft shape at a height over the floor, before any noise: in over a few blocks above
// its bottom (a flat base), out over the top fifth (a rounded top), times its density.
float lite_cloud_envelope(vec4 span, float height) {
    float bottom = span.r * LITE_CLOUD_HEIGHT_RANGE;
    float top = span.g * LITE_CLOUD_HEIGHT_RANGE;
    float thickness = max(top - bottom, 1.0);
    return span.b * smoothstep(bottom, bottom + min(LITE_CLOUD_BASE_SOFTNESS, thickness * 0.5), height)
        * (1.0 - smoothstep(top - thickness * LITE_CLOUD_TOP_SOFTNESS, top, height));
}

// How high in its span a height is: 0 at the bottom, 1 at the top.
float lite_cloud_height_in(vec4 span, float height) {
    float bottom = span.r * LITE_CLOUD_HEIGHT_RANGE;
    return clamp((height - bottom) / max(span.g * LITE_CLOUD_HEIGHT_RANGE - bottom, 1.0), 0.0, 1.0);
}

// The farthest of the scene's depths (reversed: the least) under one marched pixel, which covers
// scale by scale screen pixels (scale at most 4). The march goes as far as the farthest of them, so
// a marched pixel half over a leaf and half over the sky still holds the cloud behind the leaf, and
// the composite decides per screen pixel what of it shows.
float lite_cloud_farthest_depth(sampler2D sceneDepth, ivec2 marchPixel, int scale, ivec2 screen) {
    float farthest = 1.0;
    for (int y = 0; y < 4; y++) {
        if (y >= scale) {
            break;
        }
        for (int x = 0; x < 4; x++) {
            if (x >= scale) {
                break;
            }
            ivec2 pixel = clamp(marchPixel * scale + ivec2(x, y), ivec2(0), screen - 1);
            farthest = min(farthest, texelFetch(sceneDepth, pixel, 0).r);
        }
    }
    return farthest;
}

// The tiling noise at a point given in periods: two slices of the atlas blended for the third axis.
vec4 lite_cloud_noise(sampler2D atlas, vec3 position) {
    vec3 voxel = fract(position) * LITE_CLOUD_NOISE_PERIOD;
    float slice = voxel.z - 0.5;
    float below = floor(slice);
    float blend = slice - below;
    int first = int(mod(below, LITE_CLOUD_NOISE_PERIOD));
    int second = (first + 1) % int(LITE_CLOUD_NOISE_PERIOD);
    // In the tile, past its one-texel border: voxel 0's middle is texel 1.5.
    vec2 inTile = voxel.xy + 1.0;
    float atlasSize = LITE_CLOUD_NOISE_TILE * float(LITE_CLOUD_NOISE_TILES);
    vec2 tileA = vec2(float(first % LITE_CLOUD_NOISE_TILES), float(first / LITE_CLOUD_NOISE_TILES)) * LITE_CLOUD_NOISE_TILE;
    vec2 tileB = vec2(float(second % LITE_CLOUD_NOISE_TILES), float(second / LITE_CLOUD_NOISE_TILES)) * LITE_CLOUD_NOISE_TILE;
    vec4 a = textureLod(atlas, (tileA + inTile) / atlasSize, 0.0);
    vec4 b = textureLod(atlas, (tileB + inTile) / atlasSize, 0.0);
    return mix(a, b, blend);
}

#endif
