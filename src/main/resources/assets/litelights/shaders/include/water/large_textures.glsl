#ifndef LITELIGHTS_WATER_LARGE_TEXTURES_GLSL
#define LITELIGHTS_WATER_LARGE_TEXTURES_GLSL

#include <litelights:frame.glsl>
#include <litelights:water/uniforms.glsl>

// Large water and lava textures (idea by Tec, Big Water): one texture spans several blocks instead
// of repeating on every block. Everything is worked out here from the world position; the fluid
// meshes are vanilla's (or Sodium's) and untouched.

// The direction a flowing fluid runs on a top face. Vanilla and Sodium rotate the flow sprite so
// its v coordinate increases downstream; solving for the gradient of v over the horizontal plane
// from screen derivatives recovers that direction exactly (v is affine on each triangle). The
// angle is rounded to 1/256 of a turn so neighbouring blocks with the same flow agree to the bit.
vec2 lite_fluid_flow_direction(vec3 dpdx, vec3 dpdy, float dvdx, float dvdy) {
    float determinant = dpdx.x * dpdy.z - dpdx.z * dpdy.x;
    vec2 gradient = vec2(dvdx * dpdy.z - dvdy * dpdx.z, dpdx.x * dvdy - dpdy.x * dvdx);
    if (abs(determinant) < 1.0e-12 || dot(gradient, gradient) < 1.0e-24) {
        return vec2(0.0, 1.0);
    }
    gradient /= determinant;
    const float STEPS_PER_RADIAN = 128.0 / 3.14159265;
    float angle = round(atan(gradient.y, gradient.x) * STEPS_PER_RADIAN) / STEPS_PER_RADIAN;
    return vec2(cos(angle), sin(angle));
}

// One frame column of the strip texture. coord is in tiles (wrapped here); the gradients are of
// the unwrapped coordinate, so the mip level stays right across tile edges.
vec4 lite_fluid_strip(vec2 coord, vec2 gradientX, vec2 gradientY, float columnStart, float frameWidth, float frameHeight,
                      float frame, float frameCount) {
    vec2 inTile = fract(coord);
    float firstFrame = floor(frame);
    float blend = frame - firstFrame;
    vec2 frameSize = vec2(frameWidth, frameHeight);
    vec2 uv = vec2(columnStart, firstFrame * frameHeight) + inTile * frameSize;
    vec4 colour = textureGrad(LiteFluidData, uv, gradientX * frameSize, gradientY * frameSize);
    if (blend > 0.0) {
        float nextFrame = mod(firstFrame + 1.0, frameCount);
        vec2 nextUv = vec2(uv.x, (nextFrame + inTile.y) * frameHeight);
        colour = mix(colour, textureGrad(LiteFluidData, nextUv, gradientX * frameSize, gradientY * frameSize), blend);
    }
    return colour;
}

// The large texture's colour for a water or lava fragment. Returns false when the fragment isn't on
// the still or flowing sprite (the overlay sprite beside glass and leaves keeps vanilla's look).
bool lite_large_fluid(vec3 position, vec3 normal, vec2 uv, bool lava, vec3 dpdx, vec3 dpdy, vec2 duvdx, vec2 duvdy,
                      out vec4 texel) {
    texel = vec4(0.0);
    vec4 stillSprite = lava ? LiteLavaStillSprite : LiteWaterStillSprite;
    vec4 flowSprite = lava ? LiteLavaFlowSprite : LiteWaterFlowSprite;
    vec4 frames = lava ? LiteLavaFrames : LiteWaterFrames;
    ivec4 tileOrigin = lava ? LiteLavaTileOrigin : LiteWaterTileOrigin;
    float verticalOrigin = float(lava ? LiteWaterAnchor.w : LiteWaterAnchor.z);
    float stillTile = lava ? LiteWaterParams.w : LiteWaterParams.z;
    // Flow frames hold as many texels per block as still ones, so a flow tile covers more blocks.
    float flowTile = stillTile * LiteFluidStripLayout.y / LiteFluidStripLayout.x;
    float stillColumn = lava ? LiteFluidStripColumns.z : LiteFluidStripColumns.x;
    float flowColumn = lava ? LiteFluidStripColumns.w : LiteFluidStripColumns.y;
    vec3 local = lite_block_local(position);

    if (lite_in_sprite(uv, stillSprite)) {
        // Vanilla lays the still sprite with u along +x and v along +z, on top and bottom faces.
        vec2 coord = (vec2(tileOrigin.xy) + local.xz) / stillTile;
        texel = lite_fluid_strip(coord, dpdx.xz / stillTile, dpdy.xz / stillTile,
            stillColumn, LiteFluidStripLayout.x, LiteFluidStripLayout.z, frames.x, frames.z);
        return true;
    }
    if (!lite_in_sprite(uv, flowSprite)) {
        return false;
    }

    vec2 coord;
    vec2 gradientX;
    vec2 gradientY;
    if (abs(normal.y) > 0.3) {
        float spriteHeight = flowSprite.w - flowSprite.y;
        vec2 downstream = lite_fluid_flow_direction(dpdx, dpdy, duvdx.y / spriteHeight, duvdy.y / spriteHeight);
        vec2 across = vec2(downstream.y, -downstream.x);
        // Straight flows use the exact tile origin; diagonal ones a coarser anchor, since a rotated
        // tiling has no period that the integer block grid shares.
        bool straight = max(abs(downstream.x), abs(downstream.y)) > 0.999;
        vec2 p = (straight ? vec2(tileOrigin.zw) : vec2(LiteWaterAnchor.xy)) + local.xz;
        coord = vec2(dot(p, across), dot(p, downstream)) / flowTile;
        gradientX = vec2(dot(dpdx.xz, across), dot(dpdx.xz, downstream)) / flowTile;
        gradientY = vec2(dot(dpdy.xz, across), dot(dpdy.xz, downstream)) / flowTile;
    } else {
        // Side faces: the texture runs along the face and falls downward.
        bool runsAlongX = abs(normal.z) > abs(normal.x);
        float along = runsAlongX ? float(tileOrigin.z) + local.x : float(tileOrigin.w) + local.z;
        coord = vec2(along, -(verticalOrigin + local.y)) / flowTile;
        gradientX = vec2(runsAlongX ? dpdx.x : dpdx.z, -dpdx.y) / flowTile;
        gradientY = vec2(runsAlongX ? dpdy.x : dpdy.z, -dpdy.y) / flowTile;
    }
    texel = lite_fluid_strip(coord, gradientX, gradientY,
        flowColumn, LiteFluidStripLayout.y, LiteFluidStripLayout.w, frames.y, frames.w);
    return true;
}

#endif
