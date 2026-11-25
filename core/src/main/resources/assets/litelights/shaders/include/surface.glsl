#ifndef LITELIGHTS_SURFACE_GLSL
#define LITELIGHTS_SURFACE_GLSL

#include <litelights:frame.glsl>

// Everything a fragment shader knows about the surface it is shading, handed to the feature hooks.
struct LiteSurface {
    vec3 position;        // camera-relative
    vec3 normal;          // unit, world orientation, facing the camera
    vec2 uv;              // texture coordinate (block atlas for terrain and block models)
    vec2 textureTexels;   // size of the bound texture in texels, for pixel lock
    vec2 lightCoord;      // vanilla UV2 as floats, 0..240 block and sky light
    int material;         // LITE_MATERIAL_*, LITE_MATERIAL_DEFAULT for entities
    float shadow;         // 1 lit, 0 shadowed (sun and mob maps combined)
    float facing;         // how much the surface faces the shadow-casting light, 0..1
    int kind;             // LITE_SURFACE_*
};

const int LITE_SURFACE_TERRAIN = 0;
const int LITE_SURFACE_BLOCK_MODEL = 1;
const int LITE_SURFACE_ENTITY = 2;
const int LITE_SURFACE_ITEM = 3;

#endif
