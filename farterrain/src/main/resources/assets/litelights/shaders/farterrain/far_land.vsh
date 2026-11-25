#version 330
#extension GL_ARB_separate_shader_objects : require

// LiteLights' own far terrain: quads pulled from a texel buffer, six vertices each, no vertex
// buffer (FarQuad has the layout). Each quad points at its tile's node: the tile's origin in
// blocks and its level, so a voxel is 1 << level blocks.

#include <litelights:frame.glsl>
#include <litelights:farterrain/far_land.glsl>

uniform usamplerBuffer FarQuads;
uniform usamplerBuffer FarNodes;
uniform usamplerBuffer FarAppearances;

layout(location = 0) out vec3 cameraRelative;
layout(location = 1) out vec2 blockUv;
layout(location = 2) flat out ivec4 sprite;
layout(location = 3) flat out vec4 tint;
layout(location = 4) flat out vec2 lightCoord;
layout(location = 5) flat out int face;
layout(location = 6) flat out vec2 fade;
// A water top face's depth class (FarQuad.WATER_DEPTHS), 0 for anything else.
layout(location = 7) flat out int waterDepthClass;

const ivec2 CORNERS[6] = ivec2[](ivec2(0, 0), ivec2(1, 0), ivec2(1, 1), ivec2(0, 0), ivec2(1, 1), ivec2(0, 1));

void main() {
    int quad = gl_VertexIndex / 6;
    ivec2 corner = CORNERS[gl_VertexIndex % 6];
    uvec4 packed = texelFetch(FarQuads, quad);
    int x = int(packed.x & 31u);
    int z = int((packed.x >> 5u) & 31u);
    int y = int((packed.x >> 10u) & 1023u);
    face = int((packed.x >> 20u) & 7u);
    waterDepthClass = (packed.x >> 31u) == 0u ? int((packed.y >> 23u) & 15u) : 0;
    int blockLight = int((packed.x >> 23u) & 15u);
    int skyLight = int((packed.x >> 27u) & 15u);
    int appearance = int(packed.z & 0xFFFFu);
    int node = int(packed.z >> 16u) | int(packed.w >> 24u) << 16;
    uvec4 nodeData = texelFetch(FarNodes, node);
    ivec3 origin = ivec3(int(nodeData.x), int(nodeData.y), int(nodeData.z));
    int size = 1 << int(nodeData.w & 15u);
    // The node's swap fade: 1 dithering in, 2 out, since its start (FarLandSession.writeFade).
    float fadeStart = float(nodeData.w >> 6u);
    fade = vec2(float((nodeData.w >> 4u) & 3u), clamp((FarParams.z - fadeStart) / LITE_FAR_SWAP_FADE, 0.0, 1.0));
    #ifdef LITE_FAR_LIGHTS
    // A light's point (FarQuad.writeLight): a soft disc facing the camera at its voxel's centre,
    // drawn a little in front of it so the block it sits on doesn't hide it, and never under a
    // pixel and a half across.
    vec3 centre = vec3(origin + ivec3(x, y, z) * size - LiteCameraBlock.xyz) - LiteCameraFraction.xyz + vec3(float(size) * 0.5);
    float centreDistance = max(length(centre), 1.0);
    vec4 clip = FarViewProjection * vec4(centre * max(0.0, 1.0 - float(size) * 0.75 / centreDistance), 1.0);
    // The projection's scale: the length of the matrix's second row (the view only turns things).
    float scale = length(vec3(FarViewProjection[0][1], FarViewProjection[1][1], FarViewProjection[2][1]));
    float pixels = clamp(float(size) * 1.5 * scale / max(clip.w, 0.05) * LiteScreen.y * 0.5, LITE_FAR_LIGHT_MIN_PIXELS, LITE_FAR_LIGHT_MAX_PIXELS);
    vec2 offset = vec2(corner) * 2.0 - 1.0;
    clip.xy += offset * pixels * 2.0 * LiteScreen.zw * clip.w;
    gl_Position = clip;
    cameraRelative = centre;
    blockUv = offset;
    sprite = ivec4(0);
    face = LITE_FAR_UP;
    fade = vec2(0.0);
    uint light = packed.w & 0xFFFFFFu;
    tint = vec4(float((light >> 16u) & 255u) / 255.0, float((light >> 8u) & 255u) / 255.0, float(light & 255u) / 255.0,
        float((packed.x >> 23u) & 255u) / 255.0);
    lightCoord = vec2(0.0);
    return;
    #endif
    uvec4 spriteData = texelFetch(FarAppearances, appearance);
    int flags = int(spriteData.z);

    // The voxel's corner, in blocks within the tile, then this vertex's offset from it: whole
    // blocks for a whole face, so fract() of them is exact; sixteenths within the block for a
    // detail quad (a box's face or a plant's card, FarQuad).
    ivec3 local = ivec3(x, y, z) * size;
    vec3 within = vec3(0.0);
    ivec3 plane = local;
    bool detail = (packed.x >> 31u) != 0u;
    if (face >= LITE_FAR_CARD) {
        int a0 = int((packed.y >> 10u) & 15u);
        int a1 = int((packed.y >> 14u) & 15u) + 1;
        int b0 = int((packed.y >> 18u) & 15u);
        int b1 = int((packed.y >> 22u) & 15u) + 1;
        int rows = int((packed.y >> 5u) & 31u) + 1;
        // Corner to corner across the block: the first card from north-west to south-east, the
        // second from south-west to north-east.
        float along = float(corner.x == 0 ? a0 : a1) / 16.0;
        within = vec3(along, corner.y == 0 ? float(b0) / 16.0 : float(rows - 1) + float(b1) / 16.0,
            face == LITE_FAR_CARD ? along : 1.0 - along);
    } else {
        int extentA;
        int extentB;
        ivec3 axisA;
        ivec3 axisB;
        if (face == LITE_FAR_DOWN || face == LITE_FAR_UP) {
            axisA = ivec3(1, 0, 0);
            axisB = ivec3(0, 0, 1);
        } else if (face == LITE_FAR_NORTH || face == LITE_FAR_SOUTH) {
            axisA = ivec3(1, 0, 0);
            axisB = ivec3(0, 1, 0);
        } else {
            axisA = ivec3(0, 0, 1);
            axisB = ivec3(0, 1, 0);
        }
        bool positive = face == LITE_FAR_UP || face == LITE_FAR_SOUTH || face == LITE_FAR_EAST;
        ivec3 normalAxis = ivec3(1) - axisA - axisB;
        if (detail) {
            extentA = int(packed.y & 31u) + 1;
            extentB = int((packed.y >> 5u) & 31u) + 1;
            float a0 = float((packed.y >> 10u) & 15u) / 16.0;
            float a1 = float(((packed.y >> 14u) & 15u) + 1u) / 16.0;
            float b0 = float((packed.y >> 18u) & 15u) / 16.0;
            float b1 = float(((packed.y >> 22u) & 15u) + 1u) / 16.0;
            float depth = float((packed.y >> 26u) & 15u) / 16.0;
            // The plane depth sixteenths in from the block's own face, and the part of each block it covers.
            float planeOffset = positive ? 1.0 - depth : depth;
            within = vec3(normalAxis) * planeOffset
                + vec3(axisA) * (corner.x == 0 ? a0 : float(extentA - 1) + a1)
                + vec3(axisB) * (corner.y == 0 ? b0 : float(extentB - 1) + b1);
        } else {
            extentA = int(packed.y & 31u) + 1;
            extentB = int((packed.y >> 5u) & 1023u) + 1;
            local += positive ? normalAxis * size : ivec3(0);
            plane = local;
            local += axisA * (corner.x * extentA * size) + axisB * (corner.y * extentB * size);
            // A coarse voxel's land can end below its top (FarQuad's inset): the top face, and the top edge
            // of a column top's side faces, are drawn where it ends.
            int inset = int((packed.y >> 15u) & 255u);
            if (face == LITE_FAR_UP) {
                local.y -= inset;
                plane.y -= inset;
            } else if (corner.y == 1 && face != LITE_FAR_DOWN) {
                local.y -= inset;
            }
        }
    }

    // World minus the camera's block in integers first, so the position keeps its precision anywhere.
    vec3 cameraBase = vec3(origin - LiteCameraBlock.xyz) - LiteCameraFraction.xyz;
    vec3 position = cameraBase + vec3(local) + within;
    vec3 planePosition = detail ? position : cameraBase + vec3(plane);
    if ((flags & LITE_FAR_FLAG_WATER) != 0 && face == LITE_FAR_UP) {
        // A still water surface lies a ninth of a block under its block's top, as vanilla draws it.
        position.y -= 1.0 / 9.0;
        planePosition.y -= 1.0 / 9.0;
    }
    vec3 normal = lite_far_normal(face);
    if (face < LITE_FAR_CARD && dot(normal, planePosition) >= 0.0) {
        // Facing away: every vertex at one point outside the view, so nothing is drawn.
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }

    // Pushed back a little along the line of sight, so where the near terrain draws the same land
    // during the seam's fade it always wins the depth test.
    float distance = length(position);
    vec3 pushed = position * (1.0 + FarParams.x + 0.02 / max(distance, 1.0));
    gl_Position = FarViewProjection * vec4(pushed, 1.0);

    cameraRelative = position;
    vec3 blockPosition = vec3(local) + within;
    if (face >= LITE_FAR_CARD) {
        // Across the card, the sprite once from corner to corner.
        float across = corner.x == 0 ? float((packed.y >> 10u) & 15u) / 16.0 : float(((packed.y >> 14u) & 15u) + 1u) / 16.0;
        blockUv = vec2(across, -blockPosition.y);
    } else if (face == LITE_FAR_DOWN || face == LITE_FAR_UP) {
        blockUv = blockPosition.xz;
    } else if (face == LITE_FAR_NORTH || face == LITE_FAR_SOUTH) {
        blockUv = vec2(blockPosition.x, -blockPosition.y);
    } else {
        blockUv = vec2(blockPosition.z, -blockPosition.y);
    }
    sprite = ivec4(int(spriteData.x & 0xFFFFu), int(spriteData.x >> 16u), int(spriteData.y & 0xFFFFu), int(spriteData.y >> 16u));
    uint rgb = packed.w & 0xFFFFFFu;
    tint = vec4(float((rgb >> 16u) & 255u) / 255.0, float((rgb >> 8u) & 255u) / 255.0, float(rgb & 255u) / 255.0, float(flags));
    lightCoord = vec2(float(blockLight * 16), float(skyLight * 16));
}
