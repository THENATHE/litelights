#version 330
#extension GL_ARB_separate_shader_objects : require

// 3D haze puffs (fog097): each pixel follows its line of sight through the puff's ellipsoid. The
// density inside falls off smoothly from the middle, (1 - s^2)^2 with s the distance from the centre
// in the ellipsoid's own units, so the optical depth along the line is a polynomial in its ends and
// is integrated exactly, with no loop. The ends are where the line enters and leaves the ellipsoid,
// moved to the eye when it is inside and to the scene behind (LiteHazeDepth, the opaque scene's
// distance) where that comes first, so a puff melts into the ground and walls it touches. Two
// samples of world-space noise drifting with the wind, at about the puff's own scale, thin the
// edges into wisps.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <litelights:frame.glsl>
#include <litelights:particle/haze.glsl>

uniform sampler2D LiteHazeDepth;

layout(location = 0) in vec3 proxyPoint;
layout(location = 1) flat in vec4 puffCentre;
layout(location = 2) flat in vec4 puffShape;
layout(location = 3) flat in vec4 vertexColor;
layout(location = 4) flat in vec4 liteAir;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// No scene copy this frame (a 1 x 1 placeholder): nothing cuts the puff short.
const float NO_SCENE = 65000.0;

// The integral of (h - a u^2)^2 from 0 to u: the density along the line, with u measured from the
// point closest to the centre.
float lite_puff_integral(float u, float h, float a) {
    float u2 = u * u;
    return u * (h * h - u2 * (2.0 / 3.0 * h * a - 0.2 * a * a * u2));
}

void main() {
    vec3 eye = lite_view_origin();
    vec3 ray = normalize(proxyPoint - eye);
    int kind = int(puffShape.x + 0.5);
    vec3 centre = puffCentre.xyz;
    vec3 radii = puffCentre.w * lite_haze_radii(kind);

    // The ellipsoid's frame: its long axis along the wind, then across it, then up.
    vec3 along = lite_haze_wind_axis();
    vec3 across = vec3(-along.z, 0.0, along.x);
    vec3 offset = eye - centre;
    vec3 origin = vec3(dot(offset, along), dot(offset, across), offset.y) / radii;
    vec3 direction = vec3(dot(ray, along), dot(ray, across), ray.y) / radii;

    // The squared distance from the centre along the line is a t^2 + 2 b t + c (t in blocks).
    float a = dot(direction, direction);
    float b = dot(origin, direction);
    float c = dot(origin, origin);
    float closest = -b / a;
    // 1 - (squared distance at the closest point): 1 through the centre, 0 at the outline.
    float coreness = 1.0 - c - b * closest;
    if (coreness <= 0.0) {
        discard;
    }
    float halfChord = sqrt(coreness / a);

    float sceneDistance = NO_SCENE;
    if (textureSize(LiteHazeDepth, 0).x > 1) {
        sceneDistance = texelFetch(LiteHazeDepth, ivec2(gl_FragCoord.xy), 0).r;
    }
    // The first part of the line near the eye is left out, and the rest eases in (below).
    float enter = max(closest - halfChord, 0.35 * lite_haze_near(kind));
    float leave = min(closest + halfChord, sceneDistance);
    if (leave <= enter) {
        discard;
    }
    float density = lite_puff_integral(leave - closest, coreness, a) - lite_puff_integral(enter - closest, coreness, a);
    // In units of a straight line through the middle of a puff of the mean radius, (16/15) r.
    float meanRadius = (radii.x + radii.y + radii.z) / 3.0;
    density /= 1.0667 * meanRadius;

    float near = lite_haze_near(kind);
    // Thins out in front of the eye, so flying through a puff is a soft veil, not a wall, and
    // close to whatever the puff meets.
    float nearFade = smoothstep(0.35 * near, near, 0.5 * (enter + leave));
    float meetFade = smoothstep(0.0, 0.5 + 0.12 * puffCentre.w, sceneDistance - enter);
    float thickest = lite_haze_core_depth(kind) * density * puffShape.y * nearFade * meetFade;
    // Too faint to show even where the noise is thickest: done before paying for the noise.
    if (vertexColor.a * (1.0 - exp(-thickest)) < 0.006) {
        discard;
    }

    // Noise in world space, drifting with the wind: the middle of the part of the line inside at
    // twice the detail scale and, for puffs within 16 blocks, a third of the way in at the detail
    // scale too. Further out the finer sample is smaller than a few pixels, and most puffs are that
    // far, so it is left out (fog097-bench). Dust is streaked along the wind: its noise is squeezed
    // to half along that axis.
    vec3 middle = eye + ray * mix(enter, leave, 0.5);
    vec3 stretch = kind == LITE_HAZE_SAND ? along * 0.5 : vec3(0.0);
    vec3 worldMiddle = lite_haze_world(middle);
    worldMiddle -= stretch * dot(worldMiddle, along);
    float detail = lite_haze_detail_scale(puffCentre.w);
    float noise = lite_haze_noise(worldMiddle, detail * 2.0);
    if (length(centre - eye) < 16.0) {
        vec3 worldFirst = lite_haze_world(eye + ray * mix(enter, leave, 0.3));
        worldFirst -= stretch * dot(worldFirst, along);
        noise = 0.6 * noise + 0.4 * lite_haze_noise(worldFirst, detail);
    }
    // Near the outline only the noise's thick spots show, so the edge breaks into wisps and the
    // outline is never a clean ellipse from any side; the core stays whole. No line is ever drawn
    // at the outline itself.
    // Averaged value noise bunches up around 0.5: stretch it back out to 0..1 first.
    noise = smoothstep(0.3, 0.7, noise);
    float wisps = smoothstep(0.0, 0.5, noise * 1.2 + coreness * 0.8 - 0.75);

    float opticalDepth = thickest * wisps;
    float alpha = vertexColor.a * (1.0 - exp(-opticalDepth));
    if (alpha < 0.004) {
        discard;
    }

    // A little shading gives the puff a body: its underside a shade darker than its top.
    float shade = mix(0.84, 1.06, smoothstep(-0.9, 0.9, (middle.y - centre.y) / radii.z));
    vec4 color = vec4(vertexColor.rgb * shade, alpha) * ColorModulator;

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    if (lite_feature(LITE_FEATURE_LITE_FOG)) {
        // LiteLights' fog replaces vanilla's here, as it does on the terrain behind.
        color.rgb = mix(color.rgb, liteAir.rgb, liteAir.a);
    }
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    if (lite_feature(LITE_FEATURE_LITE_FOG)) {
        fragColor = color;
    } else {
        fragColor = apply_fog(color, fog_spherical_distance(middle), fog_cylindrical_distance(middle),
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
    }
    #endif
}
