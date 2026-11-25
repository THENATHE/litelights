#version 330
#extension GL_ARB_separate_shader_objects : require

// Far shadows at half resolution (render.feature.farterrain.FarShadows), for every pixel past the
// point where the cached sun map fades out: r = how much the sun (or moon) reaches it, g = its
// distance from the camera, for the far-terrain pass's depth-aware upsample.
//
// Screen space (always): marches from the pixel toward the light through the depth buffer. It
// finds hills and mountains between the pixel and the light that are on screen.
// LITE_FAR_SHADOW_HEIGHTFIELD (Advanced): also marches the cached heightfield of the loaded land
// around the camera (FarHeightfield), which finds casters off screen too, such as a mountain
// behind the camera. The darker of the two wins.
//
// In these passes FarSwitches holds the heightfield's values: x its lowest block height, y blocks
// it covers around the camera, z how far to march in blocks; and FarPlayer the player the camera
// follows: xyz the middle of their body, camera-relative, w how far around it the screen march
// ignores what it sees (0: nobody).

#include <litelights:farterrain/source.glsl>

uniform sampler2D SceneDepth;
#ifdef LITE_FAR_SHADOW_HEIGHTFIELD
// Top surface per block column, world x and z modulo the texture size; 0 = nothing known there.
uniform sampler2D HeightField;
#endif

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int LITE_FAR_SCREEN_STEPS = 20;
const int LITE_FAR_HEIGHT_STEPS = 40;

vec2 lite_far_project(vec3 position, mat4 viewProjection) {
    vec4 clip = viewProjection * vec4(position, 1.0);
    return clip.w > 0.05 ? clip.xy / clip.w * 0.5 + 0.5 : vec2(-1.0);
}

// The player's own body and what they hold fill much of the screen in third person; they never
// cast far shadows.
bool lite_far_is_player(vec3 seenPosition) {
    return FarPlayer.w > 0.0 && distance(seenPosition, FarPlayer.xyz) < FarPlayer.w;
}

// Only land can cast a far shadow. The sky, the sun and the moon leave the depth buffer at the far
// plane (lite_far_has_depth), and the land at the edge of the render distance, which the
// render-distance fade has mostly turned the sky's colour (brightest round a low sun), counts as
// sky too: four fifths of the way through the fade (LiteFogRange) it reads as sky, and would otherwise darken the land in front of it, as if
// the sun cast a shadow.
bool lite_far_can_cast(vec3 seenPosition) {
    float skyFromHere = mix(LiteFogRange.x, LiteFogRange.y, 0.8);
    return length(seenPosition.xz) < skyFromHere && !lite_far_is_player(seenPosition);
}

float lite_far_screen_visibility(vec3 position, float distance, vec3 light) {
    mat4 viewProjection = LiteViewProjection;
    // Steps grow with distance: a pixel 1000 blocks out looks for hills tens of blocks away, not
    // for the block next to it, which the depth buffer can't resolve there anyway.
    float step = max(1.0, distance * 0.012);
    float travelled = step * 1.5;
    for (int i = 0; i < LITE_FAR_SCREEN_STEPS; i++) {
        vec3 point = position + light * travelled;
        vec2 uv = lite_far_project(point, viewProjection);
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
            break;
        }
        float depth = texture(SceneDepth, uv).r;
        if (lite_far_has_depth(depth)) {
            vec3 seenPosition = lite_far_position(uv, depth);
            float seen = length(seenPosition);
            float along = length(point);
            // In front of the ray by more than the step error, and not so far in front that it is
            // a separate thing the ray passes behind.
            float gap = along - seen;
            if (gap > step * 0.5 && gap < travelled * 0.6 + 16.0 && lite_far_can_cast(seenPosition)) {
                vec2 edge = min(uv, 1.0 - uv);
                return 1.0 - smoothstep(0.0, 0.05, min(edge.x, edge.y));
            }
        }
        step *= 1.12;
        travelled += step;
    }
    return 1.0;
}

#ifdef LITE_FAR_SHADOW_HEIGHTFIELD
float lite_far_height_visibility(vec3 position, vec3 light) {
    float across = length(light.xz);
    if (across < 1.0e-3) {
        return 1.0;
    }
    vec2 heading = light.xz / across;
    float rise = light.y / across;
    vec3 world = vec3(LiteCameraBlock.xyz) + lite_block_local(position);
    ivec2 size = textureSize(HeightField, 0);
    float covered = FarSwitches.y;
    float step = 1.0;
    float travelled = 1.5;
    float clearance = 1.0e9;
    for (int i = 0; i < LITE_FAR_HEIGHT_STEPS && travelled < FarSwitches.z; i++) {
        vec2 column = world.xz + heading * travelled;
        if (length(column - vec2(LiteCameraBlock.xz)) > covered) {
            break;
        }
        ivec2 texel = ivec2(floor(column)) & (size - 1);
        float stored = texelFetch(HeightField, texel, 0).r * 65535.0;
        if (stored > 0.5) {
            float top = FarSwitches.x + stored;
            float rayHeight = world.y + 0.6 + rise * travelled;
            // How far above the land the ray passes, relative to how far it has come: the penumbra.
            clearance = min(clearance, (rayHeight - top) / travelled);
        }
        step *= 1.1;
        travelled += step;
    }
    return smoothstep(-0.01, 0.03, clearance);
}
#endif

void main() {
    ivec2 full = textureSize(SceneDepth, 0);
    ivec2 pixel = min(ivec2(texCoord * vec2(full)), full - 1);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    if (!lite_far_has_depth(depth)) {
        fragColor = vec4(1.0, 65000.0, 0.0, 0.0);
        return;
    }
    vec3 position = lite_far_position((vec2(pixel) + 0.5) / vec2(full), depth);
    float distance = length(position);
    vec3 light = LiteShadowLightDirection.xyz;
    if (length(position.xz) < FarShadowRange.x || light.y <= 0.0 || LiteShadowFade.y <= 0.0) {
        fragColor = vec4(1.0, distance, 0.0, 0.0);
        return;
    }
    // The sea floor takes the shadow that falls on the water above it. Before translucent terrain
    // the depth under LiteLights' own water is the floor's, and marched from down there every rise of the floor and the water's own column shaded it:
    // long dark streaks seen through the water with a low sun.
    vec3 from = position;
    if (position.y < FarShadowRange.z - 0.5) {
        from.y = FarShadowRange.z;
    }
    float visibility = lite_far_screen_visibility(from, distance, light);
    #ifdef LITE_FAR_SHADOW_HEIGHTFIELD
    visibility = min(visibility, lite_far_height_visibility(from, light));
    #endif
    fragColor = vec4(visibility, distance, 0.0, 0.0);
}
