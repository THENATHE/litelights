#version 330
#extension GL_ARB_separate_shader_objects : require

// Dropped and held items and item frames, with LiteLights shading. Same inputs and defines as
// minecraft:core/item. GUI items run in a neutral pass and look exactly like vanilla.

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <litelights:frame.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif
layout(location = 6) in vec3 Normal;

#if !defined(OIT_ALPHA_ONLY) && !defined(LITELIGHTS_SHADOW_CASTER)
uniform sampler2D Sampler1;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 4) out vec4 overlayColor;
#endif

layout(location = 5) out vec2 texCoord0;
#ifdef GLINT
layout(location = 6) out vec2 texCoordGlint;
#endif
layout(location = 7) out vec3 cameraRelativePosition;
layout(location = 8) out vec2 lightCoord;
layout(location = 9) out vec3 worldNormal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    #ifndef OIT_ALPHA_ONLY
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    #endif
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    #if !defined(OIT_ALPHA_ONLY) && !defined(LITELIGHTS_SHADOW_CASTER)
    overlayColor = texelFetch(Sampler1, UV1, 0);
    #elif !defined(OIT_ALPHA_ONLY)
    overlayColor = vec4(0.0);
    #endif

    texCoord0 = UV0;
    #ifdef GLINT
    #ifdef GLINT_SPECIAL
    texCoordGlint = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
    #else
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    #endif
    #endif
    cameraRelativePosition = Position;
    lightCoord = vec2(UV2);
    worldNormal = Normal;
}
