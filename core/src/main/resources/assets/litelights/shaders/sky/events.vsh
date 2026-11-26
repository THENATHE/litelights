#version 330
#extension GL_ARB_separate_shader_objects : require

// Sky events (the aurora, distant fireworks and rainbows). The aurora is drawn just after the sky
// and the rainbow after the clouds; for both, positions are directions from the camera scaled out
// to the sky's distance, so only the view rotation applies. WORLD is the fireworks: real places in
// the world, camera-relative, drawn as the land is.
//
// They are drawn from the eye rather than the camera position. View bobbing moves the eye about a
// tenth of a block off the camera position (the level's projection carries the bob's offset), and
// the aurora's nearest curtains are only a few blocks out at the sky's scale, so drawn from the
// camera they jumped against the stars with every step. From the eye only the bob's tilt is left,
// which turns them with the stars and the sun (see lite_view_direction in post_common.glsl).

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <litelights:frame.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;
#ifdef RAINBOW
// The direction from the eye, for the bows' angle from the point opposite the sun.
layout(location = 2) out vec3 skyDirection;
#endif
#ifdef WORLD
// The point drawn, camera-relative, for its distance and the air in front of it.
layout(location = 2) out vec3 cameraRelative;
#endif

void main() {
#ifdef WORLD
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    cameraRelative = Position;
#else
    gl_Position = ProjMat * ModelViewMat * vec4(Position + lite_view_origin(), 1.0);
#endif
    texCoord0 = UV0;
    vertexColor = Color;
#ifdef RAINBOW
    skyDirection = Position;
#endif
}
