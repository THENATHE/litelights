#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:post_common.glsl>
#include <litelights:light_post/rays_composite.glsl>

// FXAA for the world only (it runs before the hand and the HUD), gated by depth so it only
// touches geometry edges. Edges inside a texture (the pixel art of blocks and items) have no depth
// step, so they pass through untouched and stay crisp; silhouettes, block outlines against the
// sky and creases between faces get smoothed. Also adds the light rays when they are on, which
// saves their own composite pass.

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D RaysSampler;

layout(push_constant) uniform Fxaa {
    vec4 Screen;   // xy one pixel in texture units, z 1 to add light rays, w depth step that counts as an edge (relative)
    vec4 Rays;     // xy one rays texel in texture units, z 1 to show air rays only along shaded view lines
    vec4 RaysLight; // rgb colour of the scattered light
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float EDGE_THRESHOLD = 0.125;
const float EDGE_THRESHOLD_MIN = 0.0312;
const float SUBPIXEL_BLEND = 0.35;
const int SEARCH_STEPS = 8;
const float SEARCH_STRIDE[8] = float[8](1.0, 1.5, 2.0, 2.0, 2.0, 2.0, 4.0, 8.0);

float lumaAt(vec2 uv) {
    return dot(texture(SceneSampler, uv).rgb, vec3(0.299, 0.587, 0.114));
}

float depthAt(vec2 uv) {
    return texture(DepthSampler, uv).r;
}

// Reversed depth is proportional to one over the distance, so a relative difference compares
// distances, and on any flat face it changes linearly across the screen: a jump marks a
// silhouette, a bend (non-zero second difference) marks a crease between faces.
bool onGeometryEdge(vec2 uv) {
    vec2 pixel = Screen.xy;
    float centre = depthAt(uv);
    float north = depthAt(uv + vec2(0.0, pixel.y));
    float south = depthAt(uv - vec2(0.0, pixel.y));
    float east = depthAt(uv + vec2(pixel.x, 0.0));
    float west = depthAt(uv - vec2(pixel.x, 0.0));
    float largest = max(centre, max(max(north, south), max(east, west)));
    if (largest <= 0.0) {
        return false;
    }
    float jump = max(max(abs(north - centre), abs(south - centre)), max(abs(east - centre), abs(west - centre)));
    float bend = max(abs(north + south - 2.0 * centre), abs(east + west - 2.0 * centre));
    return jump > Screen.w * largest || bend > Screen.w * 0.25 * largest;
}

vec3 antialias(vec2 uv, vec3 centreColor) {
    vec2 pixel = Screen.xy;
    float lumaCentre = dot(centreColor, vec3(0.299, 0.587, 0.114));
    float lumaNorth = lumaAt(uv + vec2(0.0, pixel.y));
    float lumaSouth = lumaAt(uv - vec2(0.0, pixel.y));
    float lumaEast = lumaAt(uv + vec2(pixel.x, 0.0));
    float lumaWest = lumaAt(uv - vec2(pixel.x, 0.0));
    float lumaMax = max(lumaCentre, max(max(lumaNorth, lumaSouth), max(lumaEast, lumaWest)));
    float lumaMin = min(lumaCentre, min(min(lumaNorth, lumaSouth), min(lumaEast, lumaWest)));
    float range = lumaMax - lumaMin;
    if (range < max(EDGE_THRESHOLD_MIN, lumaMax * EDGE_THRESHOLD)) {
        return centreColor;
    }

    float lumaNorthWest = lumaAt(uv + vec2(-pixel.x, pixel.y));
    float lumaNorthEast = lumaAt(uv + vec2(pixel.x, pixel.y));
    float lumaSouthWest = lumaAt(uv + vec2(-pixel.x, -pixel.y));
    float lumaSouthEast = lumaAt(uv + vec2(pixel.x, -pixel.y));

    // How much the centre stands out from its neighbourhood: thin features blend toward it.
    float neighbourhood = (2.0 * (lumaNorth + lumaSouth + lumaEast + lumaWest)
        + lumaNorthWest + lumaNorthEast + lumaSouthWest + lumaSouthEast) / 12.0;
    float subpixel = smoothstep(0.0, 1.0, clamp(abs(neighbourhood - lumaCentre) / range, 0.0, 1.0));
    subpixel = subpixel * subpixel * SUBPIXEL_BLEND;

    // Is the edge horizontal (brightness changes going up) or vertical?
    float acrossVertical = abs(lumaNorthWest + lumaNorthEast - 2.0 * lumaNorth)
        + 2.0 * abs(lumaWest + lumaEast - 2.0 * lumaCentre)
        + abs(lumaSouthWest + lumaSouthEast - 2.0 * lumaSouth);
    float acrossHorizontal = abs(lumaNorthWest + lumaSouthWest - 2.0 * lumaWest)
        + 2.0 * abs(lumaNorth + lumaSouth - 2.0 * lumaCentre)
        + abs(lumaNorthEast + lumaSouthEast - 2.0 * lumaEast);
    bool horizontal = acrossVertical >= acrossHorizontal;

    float lumaBefore = horizontal ? lumaSouth : lumaWest;
    float lumaAfter = horizontal ? lumaNorth : lumaEast;
    float gradientBefore = abs(lumaBefore - lumaCentre);
    float gradientAfter = abs(lumaAfter - lumaCentre);
    float stepLength = horizontal ? pixel.y : pixel.x;
    float edgeLuma;
    if (gradientBefore >= gradientAfter) {
        stepLength = -stepLength;
        edgeLuma = 0.5 * (lumaBefore + lumaCentre);
    } else {
        edgeLuma = 0.5 * (lumaAfter + lumaCentre);
    }
    float gradientLimit = 0.25 * max(gradientBefore, gradientAfter);

    // Walk along the edge both ways until its contrast ends.
    vec2 onEdge = uv + (horizontal ? vec2(0.0, stepLength * 0.5) : vec2(stepLength * 0.5, 0.0));
    vec2 along = horizontal ? vec2(pixel.x, 0.0) : vec2(0.0, pixel.y);
    vec2 back = onEdge - along;
    vec2 ahead = onEdge + along;
    float endBack = lumaAt(back) - edgeLuma;
    float endAhead = lumaAt(ahead) - edgeLuma;
    bool backDone = abs(endBack) >= gradientLimit;
    bool aheadDone = abs(endAhead) >= gradientLimit;
    for (int i = 0; i < SEARCH_STEPS && !(backDone && aheadDone); i++) {
        if (!backDone) {
            back -= along * SEARCH_STRIDE[i];
            endBack = lumaAt(back) - edgeLuma;
            backDone = abs(endBack) >= gradientLimit;
        }
        if (!aheadDone) {
            ahead += along * SEARCH_STRIDE[i];
            endAhead = lumaAt(ahead) - edgeLuma;
            aheadDone = abs(endAhead) >= gradientLimit;
        }
    }

    float distanceBack = horizontal ? uv.x - back.x : uv.y - back.y;
    float distanceAhead = horizontal ? ahead.x - uv.x : ahead.y - uv.y;
    bool backIsCloser = distanceBack < distanceAhead;
    float closest = min(distanceBack, distanceAhead);
    float edgeOffset = 0.5 - closest / (distanceBack + distanceAhead);
    // Only blend when the edge end bends the right way for this pixel.
    bool centreDarker = lumaCentre < edgeLuma;
    bool endsTheRightWay = ((backIsCloser ? endBack : endAhead) < 0.0) != centreDarker;
    float offset = max(endsTheRightWay ? edgeOffset : 0.0, subpixel);

    vec2 sampleAt = uv + (horizontal ? vec2(0.0, offset * stepLength) : vec2(offset * stepLength, 0.0));
    return texture(SceneSampler, sampleAt).rgb;
}

void main() {
    vec4 scene = texture(SceneSampler, texCoord);
    vec3 color = onGeometryEdge(texCoord) ? antialias(texCoord, scene.rgb) : scene.rgb;
    if (Screen.z > 0.5) {
        vec3 added = lite_rays_to_add(RaysSampler, DepthSampler, texCoord, Rays.xy, RaysLight.rgb, Rays.z > 0.5);
        color += added * (1.0 - color);
    }
    // Alpha is passed on: it still carries bloom's emissive mask until the tonemap.
    fragColor = vec4(color, scene.a);
}
