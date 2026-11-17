#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla's cloud vertex shader with LiteLights shading. Same inputs, outputs and defines as
// minecraft:core/clouds; the face data layout is vanilla's (three signed bytes per face).
// While LiteLights draws the clouds (LITE_FEATURE_LITE_CLOUDS), faces are lit by the sun and
// fade into the haze; otherwise the output is vanilla's.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <litelights:frame.glsl>
#include <litelights:skyfog/atmosphere.glsl>

const int FLAG_MASK_DIR = 7;
const int FLAG_INSIDE_FACE = 1 << 4;
const int FLAG_USE_TOP_COLOR = 1 << 5;
const int FLAG_EXTRA_Z = 1 << 6;
const int FLAG_EXTRA_X = 1 << 7;

layout(std140) uniform CloudInfo {
    vec4 CloudColor;
    vec3 CloudOffset;
    vec3 CellSize;
};

uniform isamplerBuffer CloudFaces;

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;

// Corners of the six faces (bottom, top, north, south, west, east), four per face, packed as bits.
const int packedX = 0xF03CC3;
const int packedY = 0x6666F0;
const int packedZ = 0xC3F066;

vec3 vertex(int index) {
    return vec3(float((packedX >> index) & 1), float((packedY >> index) & 1), float((packedZ >> index) & 1));
}

const vec4[] faceColors = vec4[](
    vec4(0.7, 0.7, 0.7, 1.0),
    vec4(1.0, 1.0, 1.0, 1.0),
    vec4(0.8, 0.8, 0.8, 1.0),
    vec4(0.8, 0.8, 0.8, 1.0),
    vec4(0.9, 0.9, 0.9, 1.0),
    vec4(0.9, 0.9, 0.9, 1.0)
);

const vec3[] faceNormals = vec3[](
    vec3(0.0, -1.0, 0.0),
    vec3(0.0, 1.0, 0.0),
    vec3(0.0, 0.0, -1.0),
    vec3(0.0, 0.0, 1.0),
    vec3(-1.0, 0.0, 0.0),
    vec3(1.0, 0.0, 0.0)
);

// LiteLights cloud light: tops take the sun's colour, undersides the sky's blue-grey, sides in
// between; low sun warms whatever faces it. The cloud colour from vanilla (CLOUD_COLOR, which
// follows the day and the weather) still sets the overall brightness.
vec3 lite_cloud_light(int direction) {
    vec3 normal = faceNormals[direction];
    float sunFacing = max(dot(normal, LiteSunDirection.xyz), 0.0);
    float daylight = LiteSunColor.a;
    vec3 sunlit = mix(vec3(1.0), LiteSunColor.rgb, 0.6);
    vec3 shade = mix(vec3(0.74, 0.77, 0.84), LiteHazeColor.rgb * 1.1, 0.35);
    float up = normal.y * 0.5 + 0.5;
    vec3 light = mix(shade, sunlit, mix(0.45, 1.0, up));
    light += LiteSunGlow.rgb * 0.8 * sunFacing * (1.0 - daylight * 0.6);
    return light;
}

void main() {
    int quadVertex = gl_VertexIndex % 4;
    int index = (gl_VertexIndex / 4) * 3;

    int cellX = texelFetch(CloudFaces, index).r;
    int cellZ = texelFetch(CloudFaces, index + 1).r;
    int dirAndFlags = texelFetch(CloudFaces, index + 2).r;
    int direction = dirAndFlags & FLAG_MASK_DIR;
    bool isInsideFace = (dirAndFlags & FLAG_INSIDE_FACE) == FLAG_INSIDE_FACE;
    bool useTopColor = (dirAndFlags & FLAG_USE_TOP_COLOR) == FLAG_USE_TOP_COLOR;
    cellX = (cellX << 1) | ((dirAndFlags & FLAG_EXTRA_X) >> 7);
    cellZ = (cellZ << 1) | ((dirAndFlags & FLAG_EXTRA_Z) >> 6);
    vec3 faceVertex = vertex((direction * 4) + (isInsideFace ? 3 - quadVertex : quadVertex));
    vec3 pos = (faceVertex * CellSize) + (vec3(cellX, 0, cellZ) * CellSize) + CloudOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_spherical_distance(pos);
    if (lite_feature(LITE_FEATURE_LITE_CLOUDS)) {
        int lightDirection = useTopColor ? 1 : direction;
        vec3 light = lite_cloud_light(lightDirection);
        vec3 color = CloudColor.rgb * light;
        // Distant clouds sink into the haze along the same line of sight as the land below.
        if (lite_feature(LITE_FEATURE_LITE_FOG)) {
            vec3 view = normalize(pos);
            float haze = lite_feature(LITE_FEATURE_HAZE) ? 1.0 - exp(-LiteHazeColor.a * 0.35 * length(pos)) : 0.0;
            color = mix(color, lite_horizon_color(view), clamp(haze, 0.0, 0.85));
            // A storm hides the clouds as much as the sky behind them.
            color = mix(color, LiteStormFog.rgb, LiteSkyInfo.w);
        }
        // Lightning lights the cloud from within: the undersides and sides most, the tops less.
        float within = lightDirection == 1 ? 0.55 : (lightDirection == 0 ? 1.0 : 0.85);
        color += lite_flash_cloud(pos) * within * 2.2;
        vertexColor = vec4(color, CloudColor.a);
    } else {
        vertexColor = (useTopColor ? faceColors[1] : faceColors[direction]) * CloudColor;
    }
}
