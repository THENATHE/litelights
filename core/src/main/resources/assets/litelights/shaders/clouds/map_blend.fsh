#version 330
#extension GL_ARB_separate_shader_objects : require

// The volumetric Dynamic clouds' maps between two builds (render.feature.clouds.CloudMapTextures):
// each texel of the column maps (clouds.volume.CloudMap) blended from the build before to the
// shown one by how far the change has grown in, so between builds the clouds grow, sink, widen
// and fade with nothing switching from one frame to the next. Heights blend as heights, so a
// rising top rises. The two maps may be centred a few texels apart: the earlier one is read at
// the same place in cloud space, and where it has no column the shown one stands alone.

uniform sampler2D MapBefore;
uniform sampler2D MapAfter;

layout(push_constant) uniform MapBlend {
    vec4 Change;   // xy where the earlier map's texels are against the shown one's (texels); z the change's progress; w the size of a band's tile
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int tile = int(Change.w);
    vec4 after = texelFetch(MapAfter, texel, 0);
    ivec2 band = texel / tile;
    ivec2 earlier = texel - band * tile + ivec2(Change.xy);
    vec4 before = after;
    if (all(greaterThanEqual(earlier, ivec2(0))) && all(lessThan(earlier, ivec2(tile)))) {
        before = texelFetch(MapBefore, band * tile + earlier, 0);
    }
    fragColor = mix(before, after, Change.z);
}
