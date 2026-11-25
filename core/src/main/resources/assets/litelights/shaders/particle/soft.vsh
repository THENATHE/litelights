#version 330
#extension GL_ARB_separate_shader_objects : require

// Soft particles (snow, sand grains, and the haze kinds' classic billboards): the same inputs as
// vanilla's particle shader, so they ride in vanilla's particle batches and sorting. LITE_HAZE is the
// classic haze billboards' layer, which also needs the point on the sprite (soft.fsh).

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <litelights:skyfog/atmosphere.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec4 vertexColor;
// LiteLights' air in front of the puff: rgb its colour, a how much of the puff it hides.
layout(location = 4) out vec4 liteAir;
#ifdef LITE_HAZE
// Camera-relative point on the sprite.
layout(location = 5) out vec3 spritePoint;
#endif

// The same haze, ground mist and storm fog that terrain behind the puff gets (hooks/sky_fog.glsl),
// worked out per vertex: puffs are a few blocks wide and the air changes slowly across them. So
// a puff far off fades into the air around it instead of standing out as a round blob.
vec4 lite_air_in_front(vec3 position, float skyLight) {
    if (!lite_feature(LITE_FEATURE_LITE_FOG) || !lite_feature(LITE_FEATURE_HAZE)) {
        return vec4(0.0);
    }
    return lite_air(position, skyLight);
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    texCoord0 = UV0;
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    liteAir = lite_air_in_front(Position, clamp(float(UV2.y) / 240.0, 0.0, 1.0));
    #ifdef LITE_HAZE
    spritePoint = Position;
    #endif
}
