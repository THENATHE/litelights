#version 330
#extension GL_ARB_separate_shader_objects : require

// LiteLights' stand-in for Sodium's chunk vertex shader. Sodium's own includes unpack the vertex
// format and supply the globals, so a change to Sodium's packing reaches this shader unchanged;
// only the uniforms below are Sodium's interface. LITELIGHTS_SODIUM_CAMERA_OFFSET is defined for
// Sodium versions whose globals carry u_CameraOffset (0.9.3-beta.5 and later).

#include <sodium:globals.glsl>
#include <sodium:fog.glsl>
#include <sodium:chunk_vertex.glsl>
#include <litelights:terrain_parts.glsl>
#ifdef LITE_TERRAIN_LITELIGHTS
#include <litelights:frame.glsl>
#include <litelights:material.glsl>
#include <litelights:hooks.glsl>
#endif

layout(location = 0) out vec4 lite_vertexColor;
layout(location = 1) out vec2 lite_atlasUv;

#ifdef USE_FOG
layout(location = 2) out vec2 lite_fogDistance;
layout(location = 3) out float lite_fadeIn;
#endif
layout(location = 4) out vec3 lite_cameraRelative;
layout(location = 5) out vec2 lite_lightCoord;

// When each section of the region appeared, for its fade-in (negative: not fading).
uniform isamplerBuffer u_SectionTimeInfo;

layout(push_constant) uniform PC {
    vec3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
};

// A region is 8 x 4 x 8 sections. The draw id holds the section's cell in its region: y in bits
// 0-1, z in bits 2-4, x in bits 5-7 (Sodium's LocalSectionIndex).
vec3 lite_section_offset(uint drawId) {
    uint x = (drawId >> 5u) & 7u;
    uint y = drawId & 3u;
    uint z = (drawId >> 2u) & 7u;
    return vec3(x, y, z) * 16.0;
}

void main() {
    _vert_init();

    vec3 regionToSection = u_RegionOffset + lite_section_offset(_draw_id);
    vec3 position = _vert_position + regionToSection;
#ifdef LITELIGHTS_SODIUM_CAMERA_OFFSET
    position -= u_CameraOffset;
#endif
    lite_atlasUv = _vert_tex_diffuse_coord + _vert_tex_diffuse_coord_bias * u_TexCoordShrink;
#ifdef LITE_TERRAIN_SWAY
    if (lite_world_pass()) {
        position += lite_vegetation_offset(position, _vert_tex_diffuse_coord, _vert_tex_diffuse_coord_bias);
    }
#endif

#ifdef USE_FOG
    lite_fogDistance = getFragDistance(position);
    int appearedAt = texelFetch(u_SectionTimeInfo, int(u_RegionID * 256u + _draw_id)).r;
    lite_fadeIn = appearedAt < 0 ? 1.0 : clamp(float(u_CurrentTime - appearedAt) * u_FadePeriodInv, 0.0, 1.0);
#endif

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // The lightmap is sampled per fragment, after shadows have had their say.
    lite_vertexColor = _vert_color;
    lite_cameraRelative = position;
    // Sodium stores light with a half-texel (+8) already added; vanilla's lightmap sampling adds it
    // itself, so take it back out.
    lite_lightCoord = _vert_tex_light_coord * 256.0 - 8.0;
}
