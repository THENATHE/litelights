#version 330
#extension GL_ARB_separate_shader_objects : require

// Entities, block entities and armour, with LiteLights shading. Same inputs and defines as
// minecraft:core/entity. Solid and translucent render types (the player's skin is translucent)
// share this one shader and so get exactly the same lighting, with or without Improved
// Transparency. With LITELIGHTS_SHADOW_CASTER it draws depth only, into the mob shadow map.

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#include <minecraft:light.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <litelights:frame.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY) && !defined(LITELIGHTS_SHADOW_CASTER)
uniform sampler2D Sampler1;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
#else
layout(location = 2) out vec4 vertexColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) out vec4 overlayColor;
#endif

layout(location = 6) out vec2 texCoord0;
#ifdef GLINT
layout(location = 7) out vec2 texCoordGlint;
#endif
layout(location = 8) out vec3 cameraRelativePosition;
layout(location = 9) out vec2 lightCoord;
layout(location = 10) out vec3 worldNormal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = Color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
#endif

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY) && !defined(LITELIGHTS_SHADOW_CASTER)
    overlayColor = texelFetch(Sampler1, UV1, 0);
#elif !defined(NO_OVERLAY)
    overlayColor = vec4(0.0);
#endif

    texCoord0 = UV0;

#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif

#ifdef GLINT
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
    cameraRelativePosition = Position;
    lightCoord = vec2(UV2);
    worldNormal = Normal;
}
