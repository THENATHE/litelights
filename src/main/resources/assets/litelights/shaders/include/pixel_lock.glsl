#ifndef LITELIGHTS_PIXEL_LOCK_GLSL
#define LITELIGHTS_PIXEL_LOCK_GLSL

#include <litelights:frame.glsl>

// Pixel lock: snap a surface position to the centre of the texture texel it lies in, so an effect
// looked up at the snapped position is constant across that texel and reads as part of the pixel
// art. Every surface effect (shadows, caustics, the water surface, glint, light rays) snaps through
// these functions before its lookup.
//
// All positions are camera-relative. Call these from uniform control flow: the texel variant
// takes screen-space derivatives.

const float LITE_TEXELS_PER_BLOCK = 16.0;

bool lite_is_axis_aligned(vec3 normal) {
    vec3 a = abs(normal);
    return max(a.x, max(a.y, a.z)) > 0.999;
}

// Full blocks and other block faces on the 1/16 grid. The two in-plane axes snap to texel centres;
// the axis along the normal keeps the face plane. The tiny step against the normal makes points on
// a texel boundary pick the texel of the face they belong to.
vec3 lite_pixel_lock_face(vec3 cameraRelative, vec3 faceNormal) {
    vec3 local = lite_block_local(cameraRelative);
    vec3 inside = local - faceNormal * (1.0 / 256.0);
    vec3 snapped = (floor(inside * LITE_TEXELS_PER_BLOCK) + 0.5) / LITE_TEXELS_PER_BLOCK;
    vec3 normalAxis = step(vec3(0.5), abs(faceNormal));
    return lite_camera_relative(mix(snapped, local, normalAxis));
}

// Entities and any surface that is not axis-aligned. Finds the centre of the current texel in
// texture space, then moves the position by the matching screen offset, using the screen-space
// derivatives of the texture coordinate and of the position. textureTexels is the size of the
// bound texture in texels.
vec3 lite_pixel_lock_texel(vec3 cameraRelative, vec2 uv, vec2 textureTexels) {
    vec2 uvDx = dFdx(uv) * textureTexels;
    vec2 uvDy = dFdy(uv) * textureTexels;
    vec3 posDx = dFdx(cameraRelative);
    vec3 posDy = dFdy(cameraRelative);

    vec2 texel = uv * textureTexels;
    vec2 toCentre = (floor(texel) + 0.5) - texel;

    // Solve [uvDx uvDy] * screenOffset = toCentre for the screen offset.
    float determinant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
    if (abs(determinant) < 1e-8) {
        return cameraRelative;
    }
    vec2 screenOffset = vec2(
        uvDy.y * toCentre.x - uvDy.x * toCentre.y,
        -uvDx.y * toCentre.x + uvDx.x * toCentre.y
    ) / determinant;
    // At grazing angles one texel can span a huge screen distance; cap the move so a degenerate
    // derivative never throws the lookup far away from the surface.
    screenOffset = clamp(screenOffset, vec2(-64.0), vec2(64.0));
    return cameraRelative + posDx * screenOffset.x + posDy * screenOffset.y;
}

// Picks the face snap for axis-aligned surfaces and the texel snap for the rest. Both snaps are
// computed so the derivatives stay in uniform control flow.
vec3 lite_pixel_lock_surface(vec3 cameraRelative, vec3 normal, vec2 uv, vec2 textureTexels) {
    vec3 byTexel = lite_pixel_lock_texel(cameraRelative, uv, textureTexels);
    vec3 byFace = lite_pixel_lock_face(cameraRelative, normal);
    return lite_is_axis_aligned(normal) ? byFace : byTexel;
}

#endif
