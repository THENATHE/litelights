#version 330
#extension GL_ARB_separate_shader_objects : require

#include <litelights:post_common.glsl>

// Temporal anti-aliasing. The level is drawn with a sub-pixel jitter that changes every frame
// (TaaJitter); this pass blends the new frame into a history of the earlier ones, found again by
// reprojecting each pixel with last frame's view-projection. The history holds the settled,
// unjittered picture, so a still camera looks it up at the very same pixel.
//
// The history is only trusted where the same surface was there a frame ago: each pixel's view
// depth is kept with its colour, and a surface expected at a depth that last frame's depths
// around that spot don't have (something moved there, or moved away) drops the history. Behind
// a translucent surface the pixel shows two things that move differently while the camera moves:
// what is behind it is followed with the opaque depth copied before translucents
// (TaaOpaqueDepth), and where the two motions part by more than a fraction of a pixel neither
// history is right, so the new frame takes over. That is what keeps the bottom of a lake, and a
// mob or tree behind the water, from showing twice.
//
// What history is kept is clipped to the colour range of the new frame's 3x3 neighbourhood (in
// YCoCg, the range narrowed to the mean plus or minus a few standard deviations). Inside one texel
// of a magnified block texture the neighbourhood is that texel's flat colour, so the clip pins
// the history to it and neighbouring texels never bleed in: pixel art stays crisp. A light
// sharpen makes up for the softening of resampling the history.
//
// While the camera moves, the history is read between pixels every frame, and each read softens
// the finest detail a little; over a history of ten frames that adds up to a picture half as
// crisp as the settled one, even walking slowly. So a moving history is sharpened by how much a
// bilinear read at the same spot loses (nothing at whole-pixel steps, most at half-pixel ones),
// and the faster the camera moves, the more of the new frame is taken.

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D OpaqueDepthSampler;
uniform sampler2D HistorySampler;
uniform sampler2D PreviousOpaqueDepthSampler;

layout(push_constant) uniform Taa {
    vec4 Screen;   // xy one pixel in texture units, z 1 when the history is usable, w weight of the new frame
    vec4 Jitter;   // xy this frame's jitter in texture units, z sharpen strength, w 1 when OpaqueDepthSampler is this frame's copy
    vec4 Limits;   // x depth tolerance (relative), y weight of the new frame behind translucent surfaces, z 1 when last frame's opaque depth is usable, w unused
    vec4 Motion;   // x weight of the new frame at speed, y movement (pixels a frame) that reaches it, z history sharpen while moving, w unused
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 historyOut;

// The history's alpha for the sky (view depths are positive).
const float SKY = -1.0;

vec3 toYCoCg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

vec3 fromYCoCg(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// View depth in blocks of a reversed device depth, or SKY. The projection's depth row is the same
// for every pixel (bobbing, nausea and the view rotation don't touch it), so this is the
// inverse matrix's w row alone.
float viewDepth(float depth) {
    if (depth <= 0.0) {
        return SKY;
    }
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float ndcZ = depth;
#else
    float ndcZ = depth * 2.0 - 1.0;
#endif
    return 1.0 / max(LiteInverseViewProjection[2][3] * ndcZ + LiteInverseViewProjection[3][3], 1.0e-9);
}

// Where the surface at this pixel was on last frame's screen (xy), and its view depth then (z, or
// SKY). The sky is a direction, reprojected without the camera's movement.
vec3 previousOf(vec2 uv, float depth) {
    vec4 clip = depth <= 0.0
        ? LiteViewProjectionPrev * vec4(lite_view_direction(uv), 0.0)
        : LiteViewProjectionPrev * vec4(lite_position_from_depth(uv, depth), 1.0);
    if (clip.w <= 1.0e-6) {
        return vec3(-1.0, -1.0, SKY);
    }
    return vec3(clip.xy / clip.w * 0.5 + 0.5, depth <= 0.0 ? SKY : clip.w);
}

// How sure we can be (0..1) that a surface expected at this view depth was at previousUv last
// frame: its depth must lie within the range of the 3x3 depths there, so the jitter's sub-pixel
// steps, slopes and silhouettes never count as a change. Last frame's depths come either as
// device depths in .r (the opaque copy) or as view depths in .a (the history).
float sameSurface(sampler2D previousDepths, bool viewDepthInAlpha, vec2 previousUv, float expected) {
    ivec2 size = textureSize(previousDepths, 0);
    ivec2 centre = ivec2(previousUv * vec2(size));
    float nearest = 1.0e9;
    float farthest = 0.0;
    bool sky = false;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec4 texel = texelFetch(previousDepths, clamp(centre + ivec2(x, y), ivec2(0), size - 1), 0);
            float depth = viewDepthInAlpha ? texel.a : viewDepth(texel.r);
            if (depth <= 0.0) {
                sky = true;
            } else {
                nearest = min(nearest, depth);
                farthest = max(farthest, depth);
            }
        }
    }
    if (expected <= 0.0) {
        return sky ? 1.0 : 0.0;
    }
    if (sky) {
        farthest = 1.0e9;
    }
    float tolerance = Limits.x * expected + 0.02;
    float outside = max(nearest - expected, expected - farthest);
    return 1.0 - smoothstep(tolerance, tolerance * 2.0, outside);
}

// Catmull-Rom filtering in five bilinear reads: sharper than bilinear, so the history doesn't
// soften a little more every frame the camera moves.
vec3 catmullRom(sampler2D image, vec2 uv) {
    vec2 size = 1.0 / Screen.xy;
    vec2 position = uv * size;
    vec2 centre = floor(position - 0.5) + 0.5;
    vec2 f = position - centre;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;
    vec2 p0 = (centre - 1.0) * Screen.xy;
    vec2 p3 = (centre + 2.0) * Screen.xy;
    vec2 p12 = (centre + offset12) * Screen.xy;
    vec3 sum = texture(image, vec2(p12.x, p0.y)).rgb * w12.x * w0.y
        + texture(image, vec2(p0.x, p12.y)).rgb * w0.x * w12.y
        + texture(image, p12).rgb * w12.x * w12.y
        + texture(image, vec2(p3.x, p12.y)).rgb * w3.x * w12.y
        + texture(image, vec2(p12.x, p3.y)).rgb * w12.x * w3.y;
    float weight = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return max(sum / weight, vec3(0.0));
}

// Moves a colour toward the box's centre until it is inside the box (clipping rather than
// clamping each channel, which would change its hue).
vec3 clipToBox(vec3 colour, vec3 low, vec3 high) {
    vec3 centre = 0.5 * (low + high);
    vec3 halfSize = 0.5 * (high - low) + 1.0e-4;
    vec3 offset = colour - centre;
    vec3 units = abs(offset / halfSize);
    float largest = max(units.x, max(units.y, units.z));
    return largest > 1.0 ? centre + offset / largest : colour;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 lastPixel = textureSize(SceneSampler, 0) - 1;
    vec4 scene = texelFetch(SceneSampler, pixel, 0);
    float sceneDepth = texelFetch(DepthSampler, pixel, 0).r;

    // The new frame's neighbourhood: colour moments and range for the clip box, the mean for the
    // sharpen, and the nearest depth, so a silhouette moves with what is in front.
    vec3 sum = vec3(0.0);
    vec3 sumOfSquares = vec3(0.0);
    vec3 low = vec3(1.0e9);
    vec3 high = vec3(-1.0e9);
    vec3 neighbourSum = vec3(0.0);
    float nearestDepth = sceneDepth;
    ivec2 nearestOffset = ivec2(0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 at = clamp(pixel + ivec2(x, y), ivec2(0), lastPixel);
            vec3 neighbour = texelFetch(SceneSampler, at, 0).rgb;
            vec3 ycocg = toYCoCg(neighbour);
            sum += ycocg;
            sumOfSquares += ycocg * ycocg;
            low = min(low, ycocg);
            high = max(high, ycocg);
            if (x != 0 || y != 0) {
                neighbourSum += neighbour;
            }
            // Reversed depth: larger is nearer.
            float depth = texelFetch(DepthSampler, at, 0).r;
            if (depth > nearestDepth) {
                nearestDepth = depth;
                nearestOffset = ivec2(x, y);
            }
        }
    }
    vec3 mean = sum / 9.0;
    vec3 deviation = sqrt(max(sumOfSquares / 9.0 - mean * mean, vec3(0.0)));
    vec3 neighbourMean = neighbourSum / 8.0;

    // The new frame as drawn, and the same moved back by its jitter (steady from frame to frame)
    // for where the history can't be used.
    vec3 sharpened = clamp(scene.rgb + (scene.rgb - neighbourMean) * Jitter.z, 0.0, 1.0);
    vec3 steady = catmullRom(SceneSampler, texCoord + Jitter.xy);
    steady = clamp(steady + (steady - neighbourMean) * Jitter.z, 0.0, 1.0);
    float sceneViewDepth = viewDepth(sceneDepth);

    if (Screen.z < 0.5) {
        fragColor = vec4(steady, scene.a);
        historyOut = vec4(steady, sceneViewDepth);
        return;
    }

    // The front surface: reprojected from the nearest depth around, checked against last frame's.
    vec2 nearestUv = texCoord + vec2(nearestOffset) * Screen.xy;
    vec3 front = previousOf(nearestUv, nearestDepth);
    vec2 motion = front.xy - nearestUv;
    vec2 previousUv = texCoord + motion;
    float trust = all(greaterThanEqual(previousUv, vec2(0.0))) && all(lessThanEqual(previousUv, vec2(1.0))) ? 1.0 : 0.0;
    if (trust > 0.0) {
        trust *= sameSurface(HistorySampler, true, previousUv, front.z);
    }

    // A translucent surface in front of the opaque world: what is behind moves with its own depth.
    float newWeight = Screen.w;
    float opaqueDepth = Jitter.w > 0.5 ? texelFetch(OpaqueDepthSampler, pixel, 0).r : sceneDepth;
    float opaqueViewDepth = viewDepth(opaqueDepth);
    bool translucent = sceneViewDepth > 0.0
        && (opaqueViewDepth <= 0.0 || sceneViewDepth < opaqueViewDepth * (1.0 - Limits.x) - 0.02);
    if (translucent && trust > 0.0) {
        vec3 behind = previousOf(texCoord, opaqueDepth);
        float parting = length((behind.xy - texCoord - motion) / Screen.xy);
        trust *= 1.0 - smoothstep(0.25, 0.75, parting);
        if (Limits.z > 0.5) {
            trust *= sameSurface(PreviousOpaqueDepthSampler, false, behind.xy, behind.z);
        }
        newWeight = max(newWeight, Limits.y);
    }

    vec3 history = vec3(0.0);
    if (trust > 0.0) {
        // A wider box while still (so the jitter's own flicker doesn't shake the history), narrower
        // while things move.
        float movement = length(motion / Screen.xy);
        float spread = mix(1.25, 0.85, smoothstep(0.5, 4.0, movement));
        vec3 boxLow = max(low, mean - spread * deviation);
        vec3 boxHigh = min(high, mean + spread * deviation);
        vec3 resampled = catmullRom(HistorySampler, previousUv);
        vec3 bilinear = texture(HistorySampler, previousUv).rgb;
        resampled = max(resampled + (resampled - bilinear) * Motion.z * smoothstep(0.05, 0.5, movement), vec3(0.0));
        history = fromYCoCg(clipToBox(toYCoCg(resampled), boxLow, boxHigh));
        newWeight = max(newWeight, mix(newWeight, Motion.x, smoothstep(1.0, Motion.y, movement)));
    }

    float weight = mix(1.0, newWeight, trust);
    vec3 current = mix(steady, sharpened, trust);
    vec3 result = mix(history, current, weight);
    fragColor = vec4(result, scene.a);
    historyOut = vec4(result, sceneViewDepth);
}
