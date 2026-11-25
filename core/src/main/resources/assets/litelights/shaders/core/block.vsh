#version 330
#extension GL_ARB_separate_shader_objects : require

// Block models drawn outside chunk meshes (falling blocks, moving pistons, block entities baked as
// models), with LiteLights shading. Same inputs and defines as minecraft:core/block.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <litelights:terrain_parts.glsl>
#ifdef LITE_TERRAIN_LITELIGHTS
#include <litelights:frame.glsl>
#endif

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out vec3 cameraRelativePosition;
layout(location = 5) out vec2 lightCoord;

void main() {
    vec3 pos = Position + ModelOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color;
    texCoord0 = UV0;
    cameraRelativePosition = pos;
    lightCoord = vec2(UV2);
}
