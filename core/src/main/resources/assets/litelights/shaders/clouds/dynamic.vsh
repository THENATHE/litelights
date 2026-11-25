#version 330
#extension GL_ARB_separate_shader_objects : require

// The Dynamic clouds (render.feature.clouds.DynamicClouds): one quad per face, four vertices each,
// read from a list of faces in a texel buffer (clouds.mesh.FaceFormat, four 32-bit words a face;
// keep the bits in step with it). Cubes that grow or shrink in this generation's change are sized
// here by its progress (clouds.mesh.CloudMesher says how; CubeGrowth in the clouds tests mirrors
// it). Lit per vertex, so the fragment shader only dithers.

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <litelights:frame.glsl>
#include <litelights:skyfog/atmosphere.glsl>

layout(std140) uniform DynamicCloudInfo {
    vec4 CloudOrigin;   // xyz the faces' origin, camera-relative (wind drift included); w the cell height in blocks
    vec4 CloudColour;   // rgb the clouds' colour for the time of day (weather darkens each face by its own storminess); a moonlight's strength
    vec4 CloudChange;   // x the change's progress 0..1, y where the reach's fade starts, z where it ends (blocks); w how far inside a cloud the camera is
};

uniform usamplerBuffer DynamicCloudFaces;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec3 vertexShown;

const uint STABLE = 0u;
const uint APPEARING = 1u;
const uint FADE_IN = 1u;
const uint FADE_OUT = 2u;

// A changing cube is never thinner than this share of itself while it shows, so its face never
// lies exactly on the stable face behind it.
const float GROW_EPSILON = 1.0 / 128.0;
// How deep under a cloud's top its darkening reaches most of its strength, in blocks (the e-folding depth).
const float DEPTH_SCALE = 160.0;
// How much of the light comes from the cloud's own rounded shape (the face's shading normal), the
// rest from the cube face's own direction; and how much of vanilla's fixed shade per side is left
// to mark the cubes' edges.
const float SMOOTHING = 0.92;
const float EDGE_SHADE = 0.2;

// Each side's corners in the unit cube, wound counter-clockwise seen from outside (down, up,
// north, south, west, east).
const vec3 CORNERS[24] = vec3[](
    vec3(0.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0), vec3(1.0, 0.0, 1.0), vec3(0.0, 0.0, 1.0),
    vec3(0.0, 1.0, 0.0), vec3(0.0, 1.0, 1.0), vec3(1.0, 1.0, 1.0), vec3(1.0, 1.0, 0.0),
    vec3(0.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(1.0, 1.0, 0.0), vec3(1.0, 0.0, 0.0),
    vec3(0.0, 0.0, 1.0), vec3(1.0, 0.0, 1.0), vec3(1.0, 1.0, 1.0), vec3(0.0, 1.0, 1.0),
    vec3(0.0, 0.0, 0.0), vec3(0.0, 0.0, 1.0), vec3(0.0, 1.0, 1.0), vec3(0.0, 1.0, 0.0),
    vec3(1.0, 0.0, 0.0), vec3(1.0, 1.0, 0.0), vec3(1.0, 1.0, 1.0), vec3(1.0, 0.0, 1.0)
);

const vec3 NORMALS[6] = vec3[](
    vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, -1.0),
    vec3(0.0, 0.0, 1.0), vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0)
);

int signed10(uint bits) {
    int value = int(bits & 1023u);
    return value >= 512 ? value - 1024 : value;
}

// The axis a side is on: x 0, y 1, z 2.
int sideAxis(int side) {
    return side < 2 ? 1 : (side < 4 ? 2 : 0);
}

// FaceFormat.normal's code back to a unit vector: hemi-octahedral, in the frame of the face's side
// (its first edge, its second, and out of it).
vec3 shadingNormal(int side, uint code) {
    float u = float(code & 31u) / 30.0 * 2.0 - 1.0;
    float v = float((code >> 5u) & 31u) / 30.0 * 2.0 - 1.0;
    float a = (u + v) * 0.5;
    float b = (u - v) * 0.5;
    float outward = max(0.0, 1.0 - abs(a) - abs(b));
    vec3 edgeA = side >= 4 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 edgeB = side <= 1 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    return normalize(edgeA * a + edgeB * b + NORMALS[side] * outward);
}

// Light on a face: tops take the sun's colour, undersides the cool shade of the sky, sides between;
// a low sun warms what faces it. The cloud's rounded shape (smoothNormal) decides most of it, so a
// dome shades as a dome and the steps of its cubes don't draw lines. Deep in a cloud it darkens,
// most underneath; a stormy base darkens more, but less the higher it floats (an anvil's underside,
// lit from the side, isn't a rain deck's).
vec3 cloudLight(int side, vec3 smoothNormal, float storm, float deep, float aboveFloor) {
    vec3 normal = normalize(mix(NORMALS[side], smoothNormal, SMOOTHING));
    float daylight = LiteSunColor.a;
    float sunFacing = max(dot(normal, LiteSunDirection.xyz), 0.0);
    vec3 sunlit = mix(vec3(1.0), LiteSunColor.rgb, 0.6);
    vec3 shade = mix(vec3(0.70, 0.74, 0.82), LiteHazeColor.rgb * 1.1, 0.35);
    float up = normal.y * 0.5 + 0.5;
    vec3 light = mix(shade, sunlit, mix(0.42, 1.0, up));
    // The sun itself picks out the side of each billow that faces it, a little by day and more
    // as it sinks.
    light *= mix(0.92, 1.04, sunFacing);
    light += LiteSunGlow.rgb * 0.8 * sunFacing * (1.0 - daylight * 0.6);
    // A faint fixed shade by side, as vanilla's clouds have, so the cubes still read as cubes.
    float sideShade = side == 1 ? 1.0 : (side == 0 ? 0.8 : (side >= 4 ? 0.9 : 0.84));
    sideShade = mix(1.0, sideShade, EDGE_SHADE);
    float under = 1.0 - up;
    float lowStorm = storm * (1.0 - 0.65 * smoothstep(160.0, 700.0, aboveFloor));
    float darkening = deep * mix(0.22, 0.5, under) + lowStorm * mix(0.18, 0.55, under);
    vec3 stormTint = mix(vec3(1.0), vec3(0.86, 0.9, 1.0), storm);
    // Lower parts of the sky's cloud lie in the shade of what towers over them, so terraces and
    // the gaps between clouds read from above even with the sun overhead.
    float height = mix(0.82, 1.0, smoothstep(0.0, 320.0, aboveFloor));
    return light * sideShade * height * stormTint * (1.0 - min(darkening, 0.78));
}

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
    vertexColor = vec4(0.0);
    vertexShown = vec3(0.0);
}

void main() {
    uvec4 face = texelFetch(DynamicCloudFaces, gl_VertexIndex / 4);
    int side = int(face.x >> 28u) & 7;
    int cornerIndex = gl_VertexIndex % 4;
    vec3 corner = CORNERS[side * 4 + cornerIndex];

    uint look = face.y;
    int sizeShift = int(look & 3u);
    uint state = (look >> 2u) & 3u;
    float progress = CloudChange.x;
    vec3 low = vec3(0.0);
    vec3 high = vec3(1.0);
    if (state != STABLE) {
        float start = float((look >> 8u) & 255u) / 255.0;
        float end = float((look >> 16u) & 255u) / 255.0;
        bool appearing = state == APPEARING;
        // Before its turn an appearing cube isn't there; after its turn a disappearing one has gone.
        if (appearing ? progress < start : progress > end) {
            hide();
            return;
        }
        float done = end > start ? clamp((progress - start) / (end - start), 0.0, 1.0) : 1.0;
        float fill = max(appearing ? done : 1.0 - done, GROW_EPSILON);
        float across = (look >> 7u & 1u) != 0u ? max(appearing ? progress : 1.0 - progress, GROW_EPSILON) : 1.0;
        int from = int((look >> 4u) & 7u) - 1;
        if (from < 0) {
            // From its middle: up and down by its own turn, outward with the whole change.
            low = vec3(0.5 - 0.5 * across, 0.5 - 0.5 * fill, 0.5 - 0.5 * across);
            high = vec3(0.5 + 0.5 * across, 0.5 + 0.5 * fill, 0.5 + 0.5 * across);
        } else {
            int axis = sideAxis(from);
            if (across < 1.0) {
                low = vec3(0.5 - 0.5 * across);
                high = vec3(0.5 + 0.5 * across);
            }
            // Out from the side it grows from (an even side is the axis's low end).
            float lowEnd = (from & 1) == 0 ? 0.0 : 1.0 - fill;
            float highEnd = (from & 1) == 0 ? fill : 1.0;
            if (axis == 0) {
                low.x = lowEnd;
                high.x = highEnd;
            } else if (axis == 1) {
                low.y = lowEnd;
                high.y = highEnd;
            } else {
                low.z = lowEnd;
                high.z = highEnd;
            }
        }
    }

    // A cube is as wide as its chunk's cubes and one cell tall; a joined wall face spans several cells.
    float cell = CloudOrigin.w;
    float width = cell * float(1 << sizeShift);
    float cells = float((face.w >> 30u) + 1u);
    vec3 size = vec3(width, cell * cells, width);
    vec3 cubeCorner = vec3(float(signed10(face.x)) * width, float((face.x >> 20u) & 255u) * cell, float(signed10(face.x >> 10u)) * width);
    vec3 inCube = mix(low, high, corner);
    vec3 position = CloudOrigin.xyz + cubeCorner + inCube * size;
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);

    float storm = float((look >> 24u) & 15u) / 15.0;
    float aboveFloor = cubeCorner.y + inCube.y * size.y;
    // Each corner's own shading (clouds.mesh.CloudShading), shared with every face meeting there.
    uint normalCode = cornerIndex < 3 ? (face.z >> uint(10 * cornerIndex)) & 1023u : face.w & 1023u;
    vec3 smoothNormal = shadingNormal(side, normalCode);
    // How far under its cloud's top the corner lies: exact up and down a wall, shared along it.
    int edge = side >= 4 ? int(corner.z) : int(corner.x);
    float cloudTop = float((face.w >> uint(10 + 10 * edge)) & 1023u) * 2.0;
    float deep = 1.0 - exp(-max(cloudTop - aboveFloor, 0.0) / DEPTH_SCALE);
    vec3 colour = CloudColour.rgb * cloudLight(side, smoothNormal, storm, deep, aboveFloor);
    // Moonlight, which vanilla's night cloud colour leaves out: a silver on what faces the moon.
    float moonUp = clamp(LiteMoonDirection.w * 4.0, 0.0, 1.0) * LiteAmbientColor.a;
    vec3 moonNormal = normalize(mix(NORMALS[side], smoothNormal, SMOOTHING));
    colour += vec3(0.07, 0.08, 0.11) * CloudColour.a * (0.35 + 0.65 * max(dot(moonNormal, LiteMoonDirection.xyz), 0.0)) * moonUp
        * (1.0 - 0.6 * storm);
    float distance = length(position);
    vec3 view = position / max(distance, 1.0e-3);
    // Distant clouds sink into the haze along the same line of sight as the land below, and the
    // last of the reach fades into the horizon's colour as it dithers away.
    float fade = smoothstep(CloudChange.y, CloudChange.z, length(position.xz));
    if (lite_feature(LITE_FEATURE_LITE_FOG)) {
        // The same height haze as the land's, so it thins above the clouds as over the mountains.
        float haze = lite_feature(LITE_FEATURE_HAZE) ? 1.0 - exp(-lite_haze_depth(position, distance)) : 0.0;
        colour = mix(colour, lite_horizon_color(view), clamp(max(haze, fade * 0.7), 0.0, 0.85));
        // A storm hides the clouds as much as the sky behind them, seen from under them; from above
        // its cover is down below. Inside a cloud, its own white-out.
        float cameraAboveFloor = -CloudOrigin.y;
        float cover = max(LiteSkyInfo.w * (1.0 - smoothstep(60.0, 260.0, cameraAboveFloor)), CloudChange.w);
        colour = mix(colour, LiteStormFog.rgb, cover);
    } else {
        // Without LiteLights' fog, inside a cloud its own colour still closes in (the frame's storm
        // fog colour is the cloud's there), over a few dozen blocks.
        colour = mix(colour, LiteStormFog.rgb, CloudChange.w * (1.0 - exp(-distance * 0.12)));
    }
    // Lightning lights the cloud from within: the undersides and sides most, the tops less.
    float within = side == 1 ? 0.55 : (side == 0 ? 1.0 : 0.85);
    colour += lite_flash_cloud(position) * within * 2.2;
    vertexColor = vec4(colour, 1.0);
    // The reach's fade, and a chunk's own fade in or out over the change, dithered on complementary
    // pixels so a chunk taking another's place never leaves a hole.
    uint chunkFade = (look >> 28u) & 3u;
    vertexShown = vec3(1.0 - fade, chunkFade == FADE_IN ? progress : 2.0, chunkFade == FADE_OUT ? progress : -1.0);
}
