#ifndef LITELIGHTS_SKYFOG_CELESTIAL_GLSL
#define LITELIGHTS_SKYFOG_CELESTIAL_GLSL

// LiteLights' sun, moon, stars and shooting stars, drawn per pixel of the sky in Minecraft's pixel
// style: every body is a grid of square pixels on a plane facing the camera, like vanilla's
// textured quads, but designed here and lit by the frame's light. Each function returns colour to
// add to the sky.

#include <litelights:frame.glsl>
#include <litelights:noise.glsl>

// Sizes in units of the tangent plane at the body (tan of the angle from its centre).
const float LITE_SUN_PIXEL = 0.3 / 16.0;    // a 32-pixel square, as big as vanilla's sun quad
const float LITE_MOON_PIXEL = 0.2 / 8.0;    // a 16-pixel square, as big as vanilla's moon quad
const float LITE_STREAK_PIXEL = 0.0032;

// Pixel coordinates of a direction on the square plane facing a body that travels across the sky
// around the Z axis, like the sun and moon. Returns false when the body is behind the camera.
bool lite_body_pixel(vec3 direction, vec3 body, float pixel, out vec2 pixelCoord) {
    float facing = dot(direction, body);
    if (facing <= 0.0) {
        pixelCoord = vec2(1.0e4);
        return false;
    }
    vec3 across = vec3(0.0, 0.0, 1.0);
    vec3 along = normalize(cross(body, across));
    vec2 plane = vec2(dot(direction, along), dot(direction, across)) / facing;
    // Pixel centres sit half a pixel off the body's centre, so the square has an even width.
    pixelCoord = floor(plane / pixel) + 0.5;
    return true;
}

// A 32x32 pixel sun: an 8-pixel core, a one-pixel rim and three rings of fading glow, the rest
// of the square empty, like vanilla's sun texture.
vec3 lite_sun(vec3 direction) {
    vec2 pixel;
    if (!lite_body_pixel(direction, LiteSunDirection.xyz, LITE_SUN_PIXEL, pixel)) {
        return vec3(0.0);
    }
    float ring = max(abs(pixel.x), abs(pixel.y));
    // Toward the horizon the sun turns orange, the same way its light does.
    float low = 1.0 - smoothstep(0.0, 0.35, LiteSunDirection.w);
    vec3 core = mix(vec3(1.0, 0.97, 0.78), vec3(1.0, 0.72, 0.38), low) * 1.35;
    vec3 rim = mix(vec3(1.0, 0.88, 0.52), vec3(1.0, 0.55, 0.25), low);
    if (ring < 4.0) {
        return core;
    }
    if (ring < 5.0) {
        return rim;
    }
    if (ring < 8.0) {
        float fade = (8.0 - ring) / 3.0;
        return rim * 0.22 * fade * fade;
    }
    return vec3(0.0);
}

// Hand-placed crater pixels of the LiteLights moon, in disc pixel coordinates (centre 0, 0).
bool lite_moon_crater(vec2 pixel) {
    vec2 p = pixel - 0.5;
    return (p == vec2(-3.0, 1.0)) || (p == vec2(-2.0, 1.0)) || (p == vec2(-3.0, 2.0))
        || (p == vec2(1.0, -2.0)) || (p == vec2(2.0, -2.0)) || (p == vec2(2.0, -3.0))
        || (p == vec2(0.0, 3.0)) || (p == vec2(3.0, 1.0)) || (p == vec2(-1.0, -3.0))
        || (p == vec2(-4.0, -1.0)) || (p == vec2(1.0, 0.0));
}

// The moon: an 11-pixel disc with craters, lit by phase (LiteSkyInfo.x: 0 full, 1-3 waning,
// 4 new, 5-7 waxing), and a faint halo whose strength follows how much of it is lit.
vec3 lite_moon(vec3 direction) {
    vec2 pixel;
    if (!lite_body_pixel(direction, LiteMoonDirection.xyz, LITE_MOON_PIXEL, pixel)) {
        return vec3(0.0);
    }
    int phase = int(LiteSkyInfo.x + 0.5) & 7;
    float lit = phase == 4 ? 0.0 : (phase == 0 ? 1.0 : (phase == 2 || phase == 6 ? 0.5 : (phase == 1 || phase == 7 ? 0.8 : 0.25)));
    float radius = 5.5;
    float ring = max(abs(pixel.x), abs(pixel.y));
    if (dot(pixel, pixel) > radius * radius) {
        if (ring < 8.0) {
            return vec3(0.10, 0.11, 0.15) * lit * (8.0 - ring) / 2.5;
        }
        return vec3(0.0);
    }
    // Where the shadow edge crosses this row: c is the edge's offset as a fraction of the row's
    // half width (0 at the quarters, positive for crescents, negative for gibbous moons).
    float halfWidth = sqrt(max(radius * radius - pixel.y * pixel.y, 0.0));
    float edge = phase == 1 || phase == 7 ? -0.6 : (phase == 3 || phase == 5 ? 0.6 : 0.0);
    bool sunlit;
    if (phase == 0) {
        sunlit = true;
    } else if (phase == 4) {
        sunlit = false;
    } else if (phase < 4) {
        sunlit = pixel.x < -halfWidth * edge;
    } else {
        sunlit = pixel.x > halfWidth * edge;
    }
    if (!sunlit) {
        // Earthshine: the dark side is only just there.
        return vec3(0.025, 0.028, 0.04);
    }
    return lite_moon_crater(pixel) ? vec3(0.56, 0.59, 0.68) : vec3(0.82, 0.85, 0.92);
}

// Where a direction lands on a cube around the camera: face uv in -1..1 and a face number.
vec3 lite_cube_face(vec3 direction) {
    vec3 a = abs(direction);
    if (a.x >= a.y && a.x >= a.z) {
        return vec3(direction.zy / a.x, direction.x > 0.0 ? 0.0 : 1.0);
    }
    if (a.y >= a.z) {
        return vec3(direction.xz / a.y, direction.y > 0.0 ? 2.0 : 3.0);
    }
    return vec3(direction.xy / a.z, direction.z > 0.0 ? 4.0 : 5.0);
}

// One layer of stars: a grid of cells on the sky cube; some cells hold one square star.
// halfSize is the star's half width in radians.
vec3 lite_star_layer(vec3 face, float cells, float chance, float halfSize, float brightness, float seed) {
    vec2 grid = (face.xy * 0.5 + 0.5) * cells;
    vec2 cell = floor(grid);
    vec3 random = lite_hash33(vec3(cell, face.z * 197.0 + seed));
    if (random.z > chance) {
        return vec3(0.0);
    }
    vec2 centre = cell + 0.2 + 0.6 * random.xy;
    // A cube face spans 2 units of uv for roughly 90 degrees, so one radian is about cells / 2 cells.
    float size = halfSize * cells * 0.5;
    vec2 offset = abs(grid - centre);
    if (max(offset.x, offset.y) > size) {
        return vec3(0.0);
    }
    vec3 more = lite_hash33(random * 91.7 + seed);
    float twinkle = more.x < 0.4 ? 0.78 + 0.22 * sin(LiteTime.y * (1.3 + 2.5 * more.y) + more.z * 6.2832) : 1.0;
    vec3 tint = more.y < 0.15 ? vec3(1.0, 0.86, 0.72) : (more.y > 0.85 ? vec3(0.78, 0.86, 1.0) : vec3(1.0));
    return tint * brightness * (0.7 + 0.3 * more.z) * twinkle;
}

// Three layers of stars that turn with the sky (LiteSkyInfo.z), bright and few to faint and many.
vec3 lite_stars(vec3 direction) {
    float angle = LiteSkyInfo.z;
    float c = cos(angle);
    float s = sin(angle);
    // The sky turns around the Z axis; undo that turn to find the direction among the stars.
    vec3 starward = vec3(direction.x * c + direction.y * s, -direction.x * s + direction.y * c, direction.z);
    vec3 face = lite_cube_face(starward);
    vec3 stars = lite_star_layer(face, 48.0, 0.18, 0.0022, 1.0, 11.0);
    stars += lite_star_layer(face, 96.0, 0.11, 0.0016, 0.62, 23.0);
    stars += lite_star_layer(face, 170.0, 0.06, 0.0011, 0.40, 37.0);
    return stars;
}

// One lane of streaking stars: in each window of `period` seconds, with `chance`, one star streaks
// down the sky for `flight` seconds, from a hash of the window, the night and the lane. Lane 0 is
// the falling stars'; a meteor shower adds lanes of its own, whose windows start at other moments
// so their streaks don't line up. The settings screen's previews (LiteTime.z above 0) start the
// streaks near where the player looks. `size` scales the streak: 1 is a falling star, 2 a big
// meteor twice as wide that travels twice as far with a trail three times as long.
//
// Returns how bright the streak is here (x, 0 where there is none), how far back along its trail
// (y, 0 at the head to 1 at the end) and a hash of the streak for its colour (z).
vec3 lite_streak_shape(vec3 direction, float period, float chance, float lane, float flight, float size) {
    float night = LiteSkyInfo.y;
    bool preview = LiteTime.z > 0.0;
    float time = LiteTime.y + lane * 1.37;
    float window = floor(time / period);
    vec3 random = lite_hash33(vec3(window, night, 5.3 + lane * 2.9));
    if (random.z > chance) {
        return vec3(0.0);
    }
    float start = fract(random.z * 7.13) * (period - flight - 0.2);
    float progress = (time - window * period - start) / flight;
    if (progress < 0.0 || progress > 1.0) {
        return vec3(0.0);
    }
    vec3 origin;
    if (preview) {
        // Bearings count from north (-z) turning east (+x).
        float bearing = LiteTime.w + (random.x - 0.5) * 0.5;
        float elevation = LiteTime.z + (random.y - 0.5) * 0.2;
        origin = vec3(sin(bearing) * cos(elevation), sin(elevation), -cos(bearing) * cos(elevation));
    } else {
        float azimuth = random.x * 6.2832;
        float elevation = radians(28.0 + 37.0 * random.y);
        origin = vec3(cos(elevation) * cos(azimuth), sin(elevation), cos(elevation) * sin(azimuth));
    }
    vec3 east = normalize(cross(vec3(0.0, 1.0, 0.0), origin));
    vec3 north = cross(origin, east);
    float facing = dot(direction, origin);
    if (facing < 0.7) {
        return vec3(0.0);
    }
    vec2 plane = vec2(dot(direction, east), dot(direction, north)) / facing;
    vec2 heading = normalize(vec2(cos(random.x * 19.0), -0.7 - 0.5 * random.y));
    vec2 head = heading * 0.36 * size * progress;
    float tail = 0.16 * size * (size * 0.5 + 0.5) * min(progress * 3.0, 1.0);
    float width = LITE_STREAK_PIXEL * size;
    vec2 snapped = (floor(plane / LITE_STREAK_PIXEL) + 0.5) * LITE_STREAK_PIXEL;
    vec2 relative = snapped - head;
    float behind = -dot(relative, heading);
    float across = abs(relative.x * heading.y - relative.y * heading.x);
    if (behind < -width || behind > tail || across > width) {
        return vec3(0.0);
    }
    float back = max(behind, 0.0) / max(tail, 1.0e-4);
    return vec3((1.0 - back) * sin(progress * 3.14159), back, fract((random.x + random.y) * 43.7));
}

// A falling star's or a shower meteor's streak: warm white, fading along its trail.
vec3 lite_streak(vec3 direction, float period, float chance, float lane) {
    return vec3(1.0, 0.94, 0.84) * 2.2 * lite_streak_shape(direction, period, chance, lane, 1.0, 1.0).x;
}

// Now and then a star streaks down the sky: every night in roughly one eight-second window in
// three, and on about two nights in five (busy nights) in two windows in three. The settings
// screen's preview sends one every few seconds.
vec3 lite_shooting_star(vec3 direction) {
    if (LiteTime.z > 0.0) {
        return lite_streak(direction, 3.0, 0.95, 0.0);
    }
    float chance = lite_hash11(LiteSkyInfo.y * 0.731 + 0.17) < 0.4 ? 0.65 : 0.33;
    return lite_streak(direction, 8.0, chance, 0.0);
}

// A meteor shower night (LITE_FEATURE_METEOR_SHOWER): the same streaks, many more of them, in two
// lanes of four-second windows with a streak in nine in ten, about 27 a minute, on top of the
// night's falling stars. Its preview sends about 38 a minute.
//
// Now and then, rarely, a big meteor: twice the size, a trail three times as long and three and a
// half seconds in flight, its hot white head fading into red or blue (half each) along the trail.
// One minute-long window in twenty has one, so it comes about once in three shower nights
// (a night's dark part lasts about seven minutes); the preview shows one about every 22 seconds,
// about one streak in fifteen.
vec3 lite_meteor_shower(vec3 direction) {
    bool preview = LiteTime.z > 0.0;
    float period = preview ? 3.0 : 4.0;
    float chance = preview ? 0.95 : 0.9;
    vec3 streaks = lite_streak(direction, period, chance, 1.0) + lite_streak(direction, period, chance, 2.0);

    // Whether a window has a big meteor is its own hash: gated through lite_streak_shape's chance,
    // so rare a meteor would always start early in its window.
    float bigPeriod = preview ? 22.0 : 60.0;
    float bigWindow = floor((LiteTime.y + 3.0 * 1.37) / bigPeriod);
    if (lite_hash11(bigWindow * 0.37 + LiteSkyInfo.y * 1.71 + 0.5) > (preview ? 0.9 : 0.05)) {
        return streaks;
    }
    vec3 big = lite_streak_shape(direction, bigPeriod, 1.0, 3.0, 3.5, 2.0);
    if (big.x > 0.0) {
        vec3 tint = big.z < 0.5 ? vec3(1.0, 0.32, 0.18) : vec3(0.3, 0.55, 1.0);
        vec3 colour = mix(vec3(1.0, 0.96, 0.9), tint, smoothstep(0.0, 0.45, big.y));
        streaks += colour * 2.6 * big.x;
    }
    return streaks;
}

#endif
