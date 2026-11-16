#version 450
#extension GL_ARB_separate_shader_objects : require
#ifdef RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE
    // Set when the device has draw parameters. Vegetation needs the vertex's index within its
    // section's mesh to tell the corners of a quad apart; sections don't start on a multiple of 4
    // in the shared vertex buffer, so gl_VertexIndex alone won't do. (The compiler only declares
    // gl_BaseVertexARB for GLSL 450 and later, hence the version. The version is
    // only for shaderc, which turns every shader into SPIR-V; OpenGL drivers get SPIRV-Cross's GLSL 330
    // back, so it doesn't raise what the driver must support. Checked on a driver capped at GL 4.1.)
    #extension GL_ARB_shader_draw_parameters : require
    #define LITELIGHTS_VERTEX_IN_MESH (gl_VertexIndex - gl_BaseVertexARB)
#endif

// Vanilla chunk terrain, with LiteLights shading. Same inputs, outputs and defines as
// minecraft:core/terrain; the lightmap is sampled per fragment instead, so shadows can lower the
// sky light where they fall.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif
#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:hooks.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;
layout(location = 5) out vec3 cameraRelativePosition;
layout(location = 6) out vec2 lightCoord;

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
#ifdef LITELIGHTS_VERTEX_IN_MESH
    if (lite_world_pass()) {
        pos += lite_vegetation_offset(pos, UV0, lite_vegetation_corner(LITELIGHTS_VERTEX_IN_MESH, Color.a));
    }
#endif
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color;
    texCoord0 = UV0;
    cameraRelativePosition = pos;
    lightCoord = vec2(UV2);

    const float chunkFullyVisibleRange = 16.0;
    float dist = length(pos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
}
