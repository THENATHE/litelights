#version 330
#extension GL_ARB_separate_shader_objects : require

// The volumetric Dynamic clouds, third pass, at full resolution (render.feature.clouds.CloudVolume):
// where the cloud hides more than half of what lies behind it, its depth goes into the main depth
// buffer at the distance where it first did, so water, glass, particles and the light rays drawn
// after it sort against it as against the land. The depth test keeps what is nearer.

#include <litelights:post_common.glsl>

uniform sampler2D CloudMarch;

layout(push_constant) uniform CloudComposite {
    vec4 Scale;   // x screen pixels per marched pixel; yzw unused
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 size = textureSize(CloudMarch, 0);
    vec4 tap = texelFetch(CloudMarch, clamp(ivec2(gl_FragCoord.xy / Scale.x), ivec2(0), size - 1), 0);
    float front = floor(tap.a);
    if (front <= 0.0 || fract(tap.a) >= 0.5) {
        discard;
    }
    vec3 position = lite_view_origin() + lite_view_direction(texCoord) * front;
    vec4 clip = LiteViewProjection * vec4(position, 1.0);
    float ndcZ = clip.z / clip.w;
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    gl_FragDepth = ndcZ;
#else
    gl_FragDepth = ndcZ * 0.5 + 0.5;
#endif
    fragColor = vec4(0.0);
}
