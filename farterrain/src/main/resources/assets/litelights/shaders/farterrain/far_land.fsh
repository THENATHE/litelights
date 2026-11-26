#version 330
#extension GL_ARB_separate_shader_objects : require

// LiteLights' own far terrain, shaded as near terrain is: the block atlas's own sprites (mip level
// picked from the screen footprint, so merged faces have no seams), the material texture by atlas
// coordinate, LiteLights' light model on the stored sky and block light, the sun map's shadows
// where it reaches, glowing blocks marked for bloom, and the same air (lite_fog).
//
// LITE_FAR_WATER is far water, drawn over the scene copied after everything opaque: shaded by the
// near water's own code (lite_shade_water), so its colour, depth, see-through, reflections and
// sun glint are the near water's. LITE_FAR_LIGHTS is the lights' points, glowing in the dark,
// added on through the same air.

uniform sampler2D FarAtlas;
uniform sampler2D FarLightmap;
#ifdef LITE_FAR_WATER
// The water code reads the block atlas through this sampler, as the terrain shaders' own.
#define LITE_HOST_BLOCK_ATLAS FarAtlas
// Far water's reflections trace at most 12 steps (the near water's Fine detail takes 40), and
// what they find fades out from the render distance's edge to 256 blocks past it, so the near
// water's reflections of the land carry over the edge and thin out instead of stopping at a line.
// Past that, the sky's reflection is all a ray finds worth the cost.
#define LITE_WATER_TRACE_CAP(position) (lite_far_trace_fade(position) > 0.0 ? 12 : 0)
#define LITE_WATER_TRACE_FADE(position) lite_far_trace_fade(position)
// The floor of deep far water isn't meshed (no light reaches it), so the copy holds whatever lies
// far beyond it there, the sky or land; the near water has its dark floor under it.
#define LITE_WATER_SCENE_BEHIND(scene, position) lite_far_floor_behind(scene, position)
#endif

#include <minecraft:fog.glsl>
#ifdef LITE_FAR_WATER
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#endif
#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:shadow.glsl>
#include <litelights:lighting.glsl>
#include <litelights:surface.glsl>
#include <litelights:hooks/light_post.glsl>
#include <litelights:hooks/sky_fog.glsl>
#include <litelights:farterrain/far_land.glsl>
#ifdef LITE_FAR_WATER
// The render distance's edge in blocks, where the near water hands over.
float lite_far_edge() {
    return float(FarSeamArea.z) * 16.0;
}

float lite_far_trace_fade(vec3 position) {
    return 1.0 - smoothstep(lite_far_edge(), lite_far_edge() + 256.0, length(position.xz));
}

// What lies under far water, as the water code sees through it: the copied scene where it is a
// floor within reach of the light, and the dark of a deep floor where the copy holds something
// further down the ray than any meshed floor (FarMesher leaves out floors no light reaches, about
// fifteen blocks down).
// A deep floor about as the near terrain draws it: rock, gravel and sand in the little sky light
// that reaches the floors far terrain leaves out (sky light 4, matched against near water's deep
// floors with Biome and depth colour off, where most of the floor shows through).
vec3 lite_far_deep_floor() {
    return lite_world_light(FarLightmap, vec2(0.0, 64.0), 1.0, 1.0).rgb * 0.55;
}

vec4 lite_far_floor_behind(vec4 scene, vec3 position) {
    float surfaceW = 1.0 / gl_FragCoord.w;
    float along = max(scene.a - surfaceW, 0.0) * length(position) / surfaceW;
    float below = along * abs(normalize(position - lite_view_origin()).y);
    return vec4(mix(scene.rgb, lite_far_deep_floor(), smoothstep(14.0, 18.0, below)), scene.a);
}
#include <litelights:water/surface.glsl>
#endif

#ifdef LITE_FAR_SEAM
uniform sampler2D FarSeam;
#endif
#ifdef LITE_FAR_SNOW
#include <litelights:farterrain/far_snow.glsl>
#endif

layout(location = 0) in vec3 cameraRelative;
layout(location = 1) in vec2 blockUv;
layout(location = 2) flat in ivec4 sprite;
layout(location = 3) flat in vec4 tint;
layout(location = 4) flat in vec2 lightCoord;
layout(location = 5) flat in int face;
layout(location = 6) flat in vec2 fade;
layout(location = 7) flat in int waterDepthClass;

layout(location = 0) out vec4 fragColor;

#ifdef LITE_FAR_SEAM
// A 4 x 4 ordered dither: a steady screen-door that TAA smooths.
const float LITE_FAR_DITHER[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);

float lite_far_dither() {
    ivec2 pixel = ivec2(gl_FragCoord.xy) & 3;
    return (LITE_FAR_DITHER[(pixel.y << 2) | pixel.x] + 0.5) / 16.0;
}

// Where the near terrain has drawn a chunk, far terrain fades out of it over half a second,
// dithered, and is gone once it has (SeamCoverage). Far water reads the mask's water channel,
// which follows the near water's surface alone.
bool lite_far_hidden_by_near(vec3 normal) {
    ivec3 block = ivec3(floor(lite_block_local(cameraRelative - normal * 0.01))) + LiteCameraBlock.xyz;
    ivec2 chunk = block.xz >> 4;
    ivec2 offset = chunk - FarSeamArea.xy;
    if (abs(offset.x) > FarSeamArea.z || abs(offset.y) > FarSeamArea.z) {
        return false;
    }
    #ifdef LITE_FAR_WATER
    float covered = texelFetch(FarSeam, chunk & 255, 0).g;
    #else
    float covered = texelFetch(FarSeam, chunk & 255, 0).r;
    #endif
    if (covered <= 0.0) {
        return false;
    }
    return covered >= lite_far_dither();
}

// A level swap: the new tile draws the pixels under the fade's progress, the one it replaces the
// rest, so together they cover the ground once.
bool lite_far_faded(vec2 fade) {
    if (fade.x < 0.5) {
        return false;
    }
    bool fadingIn = fade.x < 1.5;
    return fadingIn ? lite_far_dither() >= fade.y : lite_far_dither() < fade.y;
}
#endif

#ifdef LITE_FAR_WATER
// Vanilla's sampleNearest and sampleRGSS (texture_sampling.glsl), given the derivatives: a merged
// face's coordinate wraps at every block, so the shader's own derivatives of it would jump there.
vec4 lite_far_sample_like_near(vec2 uv, vec2 du, vec2 dv, vec2 pixelSize) {
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    vec4 nearest = sampleNearest(FarAtlas, uv, pixelSize, du, dv, texelScreenSize);
    if (UseRgss != 1) {
        return nearest;
    }
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = min(pixelSize.x, pixelSize.y);
    float blendFactor = smoothstep(minPixelSize, minPixelSize * 2.0, maxTexelSize);
    float effectiveDerivative = sqrt(min(length(du), length(dv)) * max(length(du), length(dv)));
    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));
    float mipLevelLow = floor(mipLevelExact);
    const vec2 offsets[4] = vec2[](vec2(0.125, 0.375), vec2(-0.125, -0.375), vec2(0.375, -0.125), vec2(-0.375, 0.125));
    vec4 low = vec4(0.0);
    vec4 high = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUv = uv + offsets[i] * pixelSize;
        low += textureLod(FarAtlas, sampleUv, mipLevelLow);
        high += textureLod(FarAtlas, sampleUv, mipLevelLow + 1.0);
    }
    return mix(nearest, mix(low, high, fract(mipLevelExact)) * 0.25, blendFactor);
}
#endif

#ifdef LITE_FAR_WATER_PLAIN
// The depth each water top face's class stands for, in blocks (FarQuad.WATER_DEPTHS); class 0, a
// face with none given, is deep.
const float LITE_FAR_WATER_DEPTHS[16] = float[](64.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 24.0, 32.0, 48.0, 64.0);
// Past the render distance's edge the near water's own look eases into the Plain one over this
// many blocks, so the hand-over has no line.
const float LITE_FAR_PLAIN_EASE = 96.0;

// Far water's Plain look (FarLandSettings.WaterLook): the near water's colour under the player's
// Water colours and depth colour, worked out from the face's depth along the view instead of the
// scene copy, laid over the far land under it by the blend; and the sky and sun reflected off a
// level surface with the near water's Fresnel and strength, nothing traced. tintLight is the
// water's tint times its light, alpha the sprite's own. Returns the colour premultiplied by its
// coverage, and the coverage.
vec4 lite_far_plain_water(vec3 tintLight, float alpha, LiteSurface surface, float depth) {
    bool enhanced = lite_water(LITE_WATER_ENHANCED);
    bool realistic = lite_water(LITE_WATER_REALISTIC) || enhanced;
    bool large = lite_water(LITE_WATER_LARGE_WATER) && (!enhanced || lite_water(LITE_WATER_VANILLA_SPRITES));
    vec3 mean = large ? LiteWaterSpriteMeans[5].rgb : LiteWaterSpriteMeans[0].rgb;
    vec3 light = vec3(lite_water_light_level(tintLight));
    vec3 hue;
    // The texture's own average texel: the colour the near water's pattern evens out to far away.
    vec3 body = lite_water_body(mean, mean, tintLight, light, realistic, enhanced, hue);
    vec3 view = normalize(surface.position - lite_view_origin());
    float opacity = alpha;
    if (lite_water(LITE_WATER_BIOME_DEPTH)) {
        // The water a ray crosses to reach a level floor this deep.
        float thickness = min(depth / max(abs(view.y), 0.05), 64.0);
        float deep = lite_water_deepness(thickness, realistic);
        if (realistic) {
            body = mix(body, (hue * vec3(0.16, 0.30, 0.45) + vec3(0.0, 0.015, 0.03)) * light, deep);
        } else {
            body *= mix(1.0, 0.55, deep);
        }
        opacity = lite_water_depth_opacity(thickness, alpha, realistic);
    }
    // Deep floors aren't meshed (no light reaches them), so the blend would show what lies beyond
    // instead of their dark: the water covers that share with a deep floor's colour.
    float floorMissing = smoothstep(10.0, 16.0, depth);
    float reflectance = 0.0;
    vec3 reflection = vec3(0.0);
    if (lite_water(LITE_WATER_REFLECTIONS) && !lite_feature(LITE_FEATURE_CAMERA_UNDERWATER)) {
        vec3 direction = reflect(view, vec3(0.0, 1.0, 0.0));
        direction.y = max(direction.y, 0.02);
        direction = normalize(direction);
        float fresnel = 0.02 + 0.98 * pow(1.0 - clamp(-view.y, 0.0, 1.0), 5.0);
        reflectance = min(fresnel * LiteWaterParams.x * (realistic ? 0.8 : 0.5), realistic ? 0.7 : 0.4);
        reflection = lite_water_shaded_sky(direction, lite_mirror_shade(surface.shadow, surface.facing))
            * lite_water_sky_visibility(surface.lightCoord);
        if (!realistic) {
            reflection *= mix(vec3(1.0), hue / max(max(hue.r, hue.g), max(hue.b, 0.05)), 0.35);
        }
    }
    // Over the land beneath: body by its opacity, then the reflection over both.
    vec3 premultiplied = (body * opacity + lite_far_deep_floor() * (1.0 - opacity) * floorMissing) * (1.0 - reflectance)
        + reflection * reflectance;
    float covered = mix(opacity, 1.0, floorMissing);
    return vec4(premultiplied, 1.0 - (1.0 - covered) * (1.0 - reflectance));
}
#endif

#ifdef LITE_FAR_WATER_PLAIN
// Far water in the Plain look alone: lit by the stored light (no sun map past the hand-over), the
// sprite's average alpha, the Plain colour, then the light-post surface hook and the air.
void lite_far_plain_only(vec3 normal) {
    int lastLevel = int(FarParams.y);
    vec2 atlasSize = vec2(textureSize(FarAtlas, 0));
    vec2 centreUv = (vec2(sprite.xy) + vec2(sprite.zw) * 0.5) / atlasSize;
    LiteSurface surface;
    surface.position = cameraRelative;
    surface.normal = normal;
    surface.uv = centreUv;
    surface.textureTexels = atlasSize;
    surface.lightCoord = lightCoord;
    surface.material = lite_material(centreUv);
    surface.kind = LITE_SURFACE_TERRAIN;
    surface.facing = lite_light_facing(normal);
    surface.shadow = 1.0;
    vec4 worldLight = lite_world_light(FarLightmap, lightCoord, surface.shadow, surface.facing);
    float alpha = texelFetch(FarAtlas, sprite.xy >> lastLevel, lastLevel).a;
    vec4 plain = lite_far_plain_water(tint.rgb * worldLight.rgb, alpha, surface, LITE_FAR_WATER_DEPTHS[waterDepthClass]);
    vec4 color = vec4(plain.rgb / max(plain.a, 1.0e-4), plain.a);
    lite_light_surface(color, surface);
    if (!lite_fog(color, surface, FogColor)) {
        color = apply_fog(color, fog_spherical_distance(cameraRelative), fog_cylindrical_distance(cameraRelative),
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
    }
    fragColor = color;
}
#endif

#ifdef LITE_FAR_LIGHTS
// A light's glow: soft from its centre out, as strong as its lights together, only in the dark,
// and hazed by the air between as the land is (the air's own colour taken back out, since the
// glow is what it brings to the scene). The falloff, (1 - r^2)^3, is close to a bell curve: no flat
// bright middle and no edge, fading out over the whole disc. Points blend by their maximum
// (FarLandPass.LIGHTS), so many lights close together glow as brightly as the brightest of them,
// never adding up to a white blotch.
void lite_far_light() {
    float spread = dot(blockUv, blockUv);
    if (spread >= 1.0) {
        discard;
    }
    bool skyless = LiteCameraBlock.w == LITE_DIMENSION_NETHER || LiteCameraBlock.w == LITE_DIMENSION_END;
    float dark = skyless ? 1.0 : 1.0 - LiteSunColor.a;
    float inside = 1.0 - spread;
    float falloff = inside * inside * inside * LITE_FAR_LIGHT_PEAK;
    vec4 glow = vec4(tint.rgb * falloff * tint.a * dark, 1.0);
    vec4 none = vec4(0.0, 0.0, 0.0, 1.0);
    LiteSurface surface;
    surface.position = cameraRelative;
    surface.normal = vec3(0.0, 1.0, 0.0);
    surface.uv = vec2(0.0);
    surface.textureTexels = vec2(1.0);
    surface.lightCoord = vec2(0.0, 240.0);
    surface.material = 0;
    surface.kind = LITE_SURFACE_TERRAIN;
    surface.facing = 1.0;
    surface.shadow = 1.0;
    if (!lite_fog(glow, surface, FogColor) || !lite_fog(none, surface, FogColor)) {
        float spherical = fog_spherical_distance(cameraRelative);
        float cylindrical = fog_cylindrical_distance(cameraRelative);
        glow = apply_fog(vec4(tint.rgb * falloff * tint.a * dark, 1.0), spherical, cylindrical, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
        none = apply_fog(vec4(0.0, 0.0, 0.0, 1.0), spherical, cylindrical, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
    }
    fragColor = vec4(max(glow.rgb - none.rgb, vec3(0.0)), 0.0);
}
#endif

void main() {
    vec3 normal = lite_far_normal(face);
    #ifdef LITE_FAR_SEAM
    if (lite_far_hidden_by_near(normal) || lite_far_faded(fade)) {
        discard;
    }
    #endif
    #ifdef LITE_FAR_LIGHTS
    lite_far_light();
    return;
    #endif
    #ifdef LITE_FAR_WATER_PLAIN
    // Past the hand-over the Plain look needs none of the sampling, material, shadow or near
    // water's code below: the cheap path is what makes it cheaper.
    if (lite_world_pass() && LiteWaterFlags.x != 0
        && length(cameraRelative.xz) >= lite_far_edge() + LITE_FAR_PLAIN_EASE) {
        lite_far_plain_only(normal);
        return;
    }
    #endif

    // The sprite repeats once per block across a merged face. The mip level comes from how many of
    // the sprite's texels one pixel spans, taken from the unwrapped coordinate, so the wrap at
    // each block's edge never picks a wrong level.
    vec2 spriteSize = vec2(sprite.zw);
    vec2 texels = blockUv * spriteSize;
    vec2 texelsDx = dFdx(texels);
    vec2 texelsDy = dFdy(texels);
    vec2 footprint = max(abs(texelsDx), abs(texelsDy));
    float rho = max(max(footprint.x, footprint.y), 1.0e-4);
    int lastLevel = int(FarParams.y);
    int level = clamp(int(floor(log2(rho) + 0.25)), 0, lastLevel);
    ivec2 within = clamp(ivec2(fract(blockUv) * spriteSize), ivec2(0), sprite.zw - 1);
    ivec2 atlasTexel = sprite.xy + within;
    vec2 atlasSize = vec2(textureSize(FarAtlas, 0));
    int flags = int(tint.a);
    #ifdef LITE_FAR_WATER
    // Sampled as the near terrain samples it (vanilla's texture_sampling.glsl, nearest texel or RGSS
    // by the Texture filtering setting), with the footprint of the unwrapped coordinate. The water
    // code splits the colour back into the texture and its tint times light by a plainly filtered
    // sample, and what differs between the two is the near water's fine grain, which far water
    // now carries on.
    vec2 atlasUv = (vec2(sprite.xy) + fract(blockUv) * spriteSize) / atlasSize;
    vec2 duvdx = texelsDx / atlasSize;
    vec2 duvdy = texelsDy / atlasSize;
    vec4 color = lite_far_sample_like_near(atlasUv, duvdx, duvdy, 1.0 / atlasSize);
    color.rgb *= tint.rgb;
    #else
    vec4 color = texelFetch(FarAtlas, atlasTexel >> level, level);
    #ifdef LITE_FAR_CARDS
    // A plant's card shows its sprite's own shape. Its coarser mip levels hold how much of each
    // texel is covered, so the card thins out with distance through the dither (TAA smooths it)
    // instead of vanishing where the cover drops under a half. Their colour is already the
    // covered part's (the atlas's mips aren't premultiplied), so it is taken as it is.
    if (color.a < lite_far_dither()) {
        discard;
    }
    #else
    if ((flags & LITE_FAR_FLAG_CUTOUT) != 0 && color.a < 0.5) {
        // A gap in leaves or glass: the sprite's mean colour, darker, as the inside of a canopy reads.
        vec4 mean = texelFetch(FarAtlas, sprite.xy >> lastLevel, lastLevel);
        color.rgb = mean.rgb / max(mean.a, 0.25) * 0.62;
    }
    #endif
    color = vec4(color.rgb * tint.rgb * lite_far_face_shade(face), 1.0);
    vec2 atlasUv = (vec2(atlasTexel) + 0.5) / atlasSize;
    #ifdef LITE_FAR_SNOW
    if (lite_world_pass()) {
        vec3 inside = lite_block_local(cameraRelative - normal * 0.01);
        ivec3 block = ivec3(floor(inside)) + LiteCameraBlock.xyz;
        float worldY = float(LiteCameraBlock.y) + lite_block_local(cameraRelative).y;
        lite_far_land_snow(color, worldY, block, normal, lite_material(atlasUv), face >= LITE_FAR_CARD, lite_far_face_shade(face));
    }
    #endif
    #endif

    LiteSurface surface;
    surface.position = cameraRelative;
    surface.normal = normal;
    surface.uv = atlasUv;
    surface.textureTexels = atlasSize;
    surface.lightCoord = lightCoord;
    surface.material = lite_material(atlasUv);
    surface.kind = LITE_SURFACE_TERRAIN;
    surface.facing = lite_material_is_foliage(surface.material) ? 1.0 : lite_light_facing(normal);
    surface.shadow = lite_world_pass() ? lite_sun_shadow(cameraRelative, normal) : 1.0;

    vec4 worldLight = lite_world_light(FarLightmap, lightCoord, surface.shadow, surface.facing);
    color *= worldLight;
    #ifdef LITE_FAR_WATER
    #ifdef LITE_TERRAIN_WATER
    vec3 dpdx = dFdx(cameraRelative);
    vec3 dpdy = dFdy(cameraRelative);
    if (lite_world_pass() && LiteWaterFlags.x != 0) {
        #ifdef LITE_FAR_WATER_PLAIN
        // The near water's look where it hands over, easing into the Plain one past the edge. Both
        // are blended over the scene by their alpha, so they mix as premultiplied colours.
        float nearLook = 1.0 - smoothstep(lite_far_edge(), lite_far_edge() + LITE_FAR_PLAIN_EASE, length(cameraRelative.xz));
        vec4 plain = lite_far_plain_water(tint.rgb * worldLight.rgb, color.a, surface, LITE_FAR_WATER_DEPTHS[waterDepthClass]);
        if (nearLook > 0.0) {
            vec4 full = color;
            lite_shade_water(full, surface, dpdx, dpdy, duvdx, duvdy);
            plain = mix(plain, vec4(full.rgb * full.a, full.a), nearLook);
        }
        color = vec4(plain.rgb / max(plain.a, 1.0e-4), plain.a);
        #else
        lite_shade_water(color, surface, dpdx, dpdy, duvdx, duvdy);
        #endif
    }
    #endif
    #else
    color.a = 1.0;
    #endif
    if (lite_world_pass()) {
        lite_light_surface(color, surface);
    }
    if (!lite_fog(color, surface, FogColor)) {
        color = apply_fog(color, fog_spherical_distance(cameraRelative), fog_cylindrical_distance(cameraRelative),
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
    }
    fragColor = color;
}
