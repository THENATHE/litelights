#version 330
#extension GL_ARB_separate_shader_objects : require

// 3D haze puffs (fog097). Each puff arrives as four vertices at its centre, in vanilla's particle
// format so it rides vanilla's particle batches, sorting and Improved Transparency; the corners are
// worked out here. UV0 carries the puff: the signs say which corner, |u| - 1 is its size in blocks,
// and |v| - 1 is its kind (ParticleKind.Haze, 0 to 3) plus four times a seed byte (ParticleBatch).
//
// The quad is only a proxy that covers the puff: the fragment shader (puff.fsh) traces the puff's
// ellipsoid itself. It faces the eye, sits in front of the puff's bounding sphere so it never cuts
// into the ground or walls, and is just large enough to cover the sphere's outline. With the eye
// inside or right next to the puff it covers the whole view instead, just past the near plane.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <litelights:skyfog/atmosphere.glsl>
#include <litelights:particle/haze.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler2;

// Camera-relative point on the proxy; the fragment's line of sight runs from the eye through it.
layout(location = 0) out vec3 proxyPoint;
// xyz the puff's centre (camera-relative), w its size in blocks.
layout(location = 1) flat out vec4 puffCentre;
// x the kind, y how thick this puff is (its seed and the clumps where it floats), z, w unused.
layout(location = 2) flat out vec4 puffShape;
layout(location = 3) flat out vec4 vertexColor;
// LiteLights' air in front of the puff: rgb its colour, a how much of the puff it hides.
layout(location = 4) flat out vec4 liteAir;

// How far past the bounding sphere the eye must be for the facing quad; closer, the full-view quad.
const float NEAR_MARGIN = 0.35;
// Where the full-view quad sits: past the near plane (0.05), and well before anything a puff touches.
const float FULL_VIEW_DISTANCE = 0.1;

vec4 lite_air_in_front(vec3 position, float skyLight) {
    if (!lite_feature(LITE_FEATURE_LITE_FOG) || !lite_feature(LITE_FEATURE_HAZE)) {
        return vec4(0.0);
    }
    return lite_air(position, skyLight);
}

void main() {
    vec3 centre = Position;
    vec2 corner = sign(UV0);
    float size = abs(UV0.x) - 1.0;
    float code = floor(abs(UV0.y) - 1.0 + 0.5);
    int kind = int(mod(code, 4.0));
    float seed = floor(code / 4.0) / 255.0;

    vec3 radii = size * lite_haze_radii(kind);
    float bound = max(radii.x, max(radii.y, radii.z));
    vec3 eye = lite_view_origin();
    vec3 toCentre = centre - eye;
    float distance = length(toCentre);

    // The view's axes in camera-relative space: the rows of the view rotation.
    vec3 viewRight = vec3(ModelViewMat[0][0], ModelViewMat[1][0], ModelViewMat[2][0]);
    vec3 viewUp = vec3(ModelViewMat[0][1], ModelViewMat[1][1], ModelViewMat[2][1]);
    vec3 viewForward = -vec3(ModelViewMat[0][2], ModelViewMat[1][2], ModelViewMat[2][2]);

    vec3 position;
    if (distance > bound + NEAR_MARGIN) {
        vec3 direction = toCentre / distance;
        // Turned about the line of sight to keep world up, so the quad never rolls with the camera.
        vec3 right = cross(direction, vec3(0.0, 1.0, 0.0));
        float rightLength = length(right);
        right = rightLength > 1.0e-3 ? right / rightLength : viewRight;
        vec3 up = cross(right, direction);
        float planeDistance = distance - bound;
        // Two bounds on the outline, each enough, so the smaller is used along each side: the
        // bounding sphere's exact outline, and the ellipsoid's own reach along that side (every point
        // of it is at least planeDistance ahead, and the quad is at planeDistance). The second keeps
        // flat mist and long dust from paying for a square as wide as their longest axis.
        float sphereHalf = planeDistance * bound / sqrt(distance * distance - bound * bound);
        vec3 along = lite_haze_wind_axis();
        vec3 across = vec3(-along.z, 0.0, along.x);
        vec2 reach = vec2(length(radii * vec3(dot(right, along), dot(right, across), right.y)),
            length(radii * vec3(dot(up, along), dot(up, across), up.y)));
        vec2 halfSize = min(reach, vec2(sphereHalf)) * 1.02;
        position = eye + direction * planeDistance + right * (corner.x * halfSize.x) + up * (corner.y * halfSize.y);
    } else {
        vec2 halfView = FULL_VIEW_DISTANCE / vec2(ProjMat[0][0], ProjMat[1][1]) * 1.1;
        position = eye + viewForward * FULL_VIEW_DISTANCE + viewRight * (corner.x * halfView.x) + viewUp * (corner.y * halfView.y);
    }
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);

    proxyPoint = position;
    puffCentre = vec4(centre, size);
    // Some puffs thicker than others, and more of them thick where the slow clump noise says so.
    float thickness = mix(0.7, 1.3, seed) * lite_haze_clumps(lite_haze_world(centre));
    puffShape = vec4(float(kind), thickness, 0.0, 0.0);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    liteAir = lite_air_in_front(centre, clamp(float(UV2.y) / 240.0, 0.0, 1.0));
}
