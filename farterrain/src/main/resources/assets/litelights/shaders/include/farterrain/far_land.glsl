#ifndef LITELIGHTS_FARTERRAIN_FAR_LAND_GLSL
#define LITELIGHTS_FARTERRAIN_FAR_LAND_GLSL

// LiteLights' own far terrain (render.feature.farterrain.land): the push constants both stages
// read (FarLandPass.writeConstants must match) and the quad layout (FarQuad).

layout(push_constant) uniform FarLand {
    mat4 FarViewProjection;   // camera-relative position -> clip space, as the level is drawn (TAA jitter included)
    ivec4 FarSeamArea;        // xy the camera's chunk, z how many chunks round it the seam mask holds, w unused
    vec4 FarParams;           // x how far each position is pushed back (a share of its distance), y the atlas's last mip level, z hundredths of a second since the world opened (the swap fade's clock), w unused
};

const int LITE_FAR_DOWN = 0;
const int LITE_FAR_UP = 1;
const int LITE_FAR_NORTH = 2;
const int LITE_FAR_SOUTH = 3;
const int LITE_FAR_WEST = 4;
const int LITE_FAR_EAST = 5;
// A plant's two crossed cards (FarQuad.CARD, CARD_CROSSED): lit as tops, from both sides.
const int LITE_FAR_CARD = 6;

// A level swap's dither fade (FarLandSession.SWAP_FADE_MILLIS), in hundredths of a second.
const float LITE_FAR_SWAP_FADE = 25.0;

// A light's point (LITE_FAR_LIGHTS): never smaller across than this many pixels, nor larger.
const float LITE_FAR_LIGHT_MIN_PIXELS = 1.5;
const float LITE_FAR_LIGHT_MAX_PIXELS = 24.0;

const int LITE_FAR_FLAG_CUTOUT = 1;
const int LITE_FAR_FLAG_WATER = 2;

vec3 lite_far_normal(int face) {
    if (face >= LITE_FAR_CARD) {
        return vec3(0.0, 1.0, 0.0);
    }
    if (face == LITE_FAR_DOWN) {
        return vec3(0.0, -1.0, 0.0);
    }
    if (face == LITE_FAR_UP) {
        return vec3(0.0, 1.0, 0.0);
    }
    if (face == LITE_FAR_NORTH) {
        return vec3(0.0, 0.0, -1.0);
    }
    if (face == LITE_FAR_SOUTH) {
        return vec3(0.0, 0.0, 1.0);
    }
    if (face == LITE_FAR_WEST) {
        return vec3(-1.0, 0.0, 0.0);
    }
    return vec3(1.0, 0.0, 0.0);
}

// Vanilla's face shading: tops full, sides 0.8 and 0.6, bottoms half, as near terrain's vertex colour has
// it; plants' cards aren't shaded by direction, as vanilla's aren't.
float lite_far_face_shade(int face) {
    if (face == LITE_FAR_UP || face >= LITE_FAR_CARD) {
        return 1.0;
    }
    if (face == LITE_FAR_DOWN) {
        return 0.5;
    }
    return face == LITE_FAR_NORTH || face == LITE_FAR_SOUTH ? 0.8 : 0.6;
}

#endif
