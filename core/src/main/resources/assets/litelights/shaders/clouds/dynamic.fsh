#version 330
#extension GL_ARB_separate_shader_objects : require

// The Dynamic clouds' fragments: opaque, lit per vertex (dynamic.vsh). The last of the reach, and a
// chunk fading in or out as it comes into reach or changes cube size, are dithered rather than
// blended, so the cubes stay opaque (no sorting, full alpha for bloom's mask) and a TAA frame
// smooths the pattern out.

layout(location = 0) in vec4 vertexColor;
// x how much of the reach's fade is left; y a fading-in chunk shows where the pattern is under
// this (2: not fading in); z a fading-out chunk where it is at or over this (-1: not fading out).
layout(location = 1) in vec3 vertexShown;

layout(location = 0) out vec4 fragColor;

const float BAYER[16] = float[](
    0.0 / 16.0, 8.0 / 16.0, 2.0 / 16.0, 10.0 / 16.0,
    12.0 / 16.0, 4.0 / 16.0, 14.0 / 16.0, 6.0 / 16.0,
    3.0 / 16.0, 11.0 / 16.0, 1.0 / 16.0, 9.0 / 16.0,
    15.0 / 16.0, 7.0 / 16.0, 13.0 / 16.0, 5.0 / 16.0
);

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy) & 3;
    float pattern = BAYER[pixel.y * 4 + pixel.x] + 0.5 / 16.0;
    if (vertexShown.x < 1.0 && vertexShown.x <= pattern) {
        discard;
    }
    if (pattern >= vertexShown.y || pattern < vertexShown.z) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, 1.0);
}
