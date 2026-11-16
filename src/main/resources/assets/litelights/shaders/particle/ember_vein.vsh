#version 330
#extension GL_ARB_separate_shader_objects : require

// Ember veins: glowing cracks laid over the faces of flammable blocks near fire. The same inputs as
// vanilla's particle shader, so they ride in LiteLights' particle batch (its solid phase, after the
// terrain). The vertex colour carries the glow: rgb its colour, alpha how bright it is now.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <litelights:skyfog/atmosphere.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec4 vertexColor;
// How much of the glow LiteLights' air in front hides, as it hides the block under it.
layout(location = 4) out float liteAirCover;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    texCoord0 = UV0;
    vertexColor = Color;
    liteAirCover = 0.0;
    if (lite_feature(LITE_FEATURE_LITE_FOG) && lite_feature(LITE_FEATURE_HAZE)) {
        liteAirCover = lite_air(Position, clamp(float(UV2.y) / 240.0, 0.0, 1.0)).a;
    }
}
