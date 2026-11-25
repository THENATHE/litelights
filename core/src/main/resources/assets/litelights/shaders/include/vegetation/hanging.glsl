#ifndef LITELIGHTS_VEGETATION_HANGING_GLSL
#define LITELIGHTS_VEGETATION_HANGING_GLSL

// Swinging lanterns, chains and hanging signs (lanterns099), included by hooks/vegetation.glsl after
// the wind it uses. Each hanging column (a hanging lantern, hanging sign or loose chain end, with the
// vertical chain above it) is one rigid pendulum turning about the top of its column, so a lantern
// and its chain never part. render.feature.vegetation.SwingingLanterns finds the columns near the
// camera and lists them in the frame block (LiteSwing, LiteSwingCells, LiteSwingColumns); HangingSwing
// is the same maths in Java, which turns a hanging sign's text with its board. Keep the two in step.
//
// Nothing here needs the vertex's corner or material: a vertex belongs to a column when it lies
// inside the column's block in x and z (never on its edge, where the blocks around it have their
// corners) and between the column's bottom and its pivot. Only lanterns, chains and hanging signs
// stand there, so this runs on vanilla's renderer without draw parameters as well as on Sodium.

const int LITE_SWING_MAX_COLUMNS = 64;
const float LITE_SWING_FADE_START = 64.0;
const float LITE_SWING_FADE_END = 96.0;
// Vertices this close to a block's side in x or z belong to the blocks around it as much as to it.
const float LITE_SWING_EDGE = 0.02;

const float LITE_SWING_CALM = 0.05;
const float LITE_SWING_STORM = 0.22;
const float LITE_SWING_LEAN = 0.12;
const float LITE_SWING_ACROSS = 0.45;
const float LITE_SWING_SHORT = 0.6;

uint lite_swing_hash(int x, int z) {
    uint h = uint(x) * 0x9E3779B1u ^ uint(z) * 0x85EBCA77u;
    h ^= h >> 15u;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12u;
    return h;
}

// How far a column leans, in radians, as a horizontal vector toward where its bottom swings.
// column: x and z its block, z its bottom in sixteenths << 12 with its cycles per hour, w its pivot
// in sixteenths << 5 with its mode (0 free, else 1 + the sign's rotation segment).
vec2 lite_swing_tilt(ivec4 column, float seconds) {
    float span = float((column.w >> 5) - (column.z >> 12)) / 16.0;
    float pendulum = max(span - 0.4, 0.3);
    float reach = sqrt(LITE_SWING_SHORT / max(pendulum, LITE_SWING_SHORT));
    int cycles = column.z & 4095;
    uint hash = lite_swing_hash(column.x, column.y);
    float phase = float(hash & 1023u) * (LITE_TAU / 1024.0);
    float acrossPhase = float((hash >> 10u) & 1023u) * (LITE_TAU / 1024.0);
    float along = sin(lite_wind_rate(float(cycles)) * seconds + phase);
    float across = sin(lite_wind_rate(float(cycles + (cycles >> 3))) * seconds + acrossPhase);

    vec3 gust = lite_wind_gust(vec2(column.xy & ivec2(255)) + 0.5, seconds);
    float strength = LiteSwing.x;
    float gustiness = 0.3 + 0.5 * strength;
    float swing = reach * (LITE_SWING_CALM + LITE_SWING_STORM * strength) * (1.0 - gustiness + gustiness * gust.z);
    float lean = reach * LITE_SWING_LEAN * strength * gust.z;
    vec2 tilt = gust.xy * (lean + swing * along) + vec2(-gust.y, gust.x) * (swing * LITE_SWING_ACROSS * across);

    int mode = column.w & 31;
    if (mode != 0) {
        // A sign hung by two chains, or from its bar, swings only toward and away from its face.
        float angle = float(mode - 1) * (LITE_TAU / 16.0);
        vec2 normal = vec2(-sin(angle), cos(angle));
        tilt = normal * dot(tilt, normal);
    }
    return tilt;
}

// The offset that swings a vertex of this column: a turn about the pivot, about the horizontal axis
// at right angles to the tilt. position is camera-relative and unmoved.
vec3 lite_swing_turn(vec3 position, ivec4 column) {
    vec3 columnCorner = vec3(ivec3(column.x, 0, column.y) - ivec3(LiteCameraBlock.x, 0, LiteCameraBlock.z));
    float cameraY = float(LiteCameraBlock.y) + LiteCameraFraction.y;
    vec3 pivot = vec3(columnCorner.x + 0.5 - LiteCameraFraction.x, float(column.w >> 5) / 16.0 - cameraY,
        columnCorner.z + 0.5 - LiteCameraFraction.z);
    vec3 weight = vec3(pivot.x, float(column.z >> 12) / 16.0 - cameraY, pivot.z);
    float fade = 1.0 - smoothstep(LITE_SWING_FADE_START, LITE_SWING_FADE_END, length(weight));

    vec2 tilt = lite_swing_tilt(column, LiteTime.y) * (fade * LiteSwing.y);
    float angle = length(tilt);
    if (angle < 1.0e-5) {
        return vec3(0.0);
    }
    vec3 axis = vec3(-tilt.y, 0.0, tilt.x) / angle;
    vec3 arm = position - pivot;
    float c = cos(angle);
    float s = sin(angle);
    vec3 turned = arm * c + cross(axis, arm) * s + axis * (dot(axis, arm) * (1.0 - c));
    return turned - arm;
}

// position: camera-relative, unmoved. Returns the camera-relative offset to add (zero for every
// vertex that isn't part of a swinging column).
vec3 lite_hanging_offset(vec3 position) {
    int count = int(LiteSwing.z);
    if (count <= 0 || dot(position, position) > LITE_SWING_FADE_END * LITE_SWING_FADE_END) {
        return vec3(0.0);
    }
    vec3 blockLocal = lite_block_local(position);
    vec2 inside = fract(blockLocal.xz);
    if (any(lessThan(inside, vec2(LITE_SWING_EDGE))) || any(greaterThan(inside, vec2(1.0 - LITE_SWING_EDGE)))) {
        return vec3(0.0);
    }
    ivec2 block = LiteCameraBlock.xz + ivec2(floor(blockLocal.xz));
    int row = block.y & 31;
    if (((LiteSwingCells[row >> 2][row & 3] >> (block.x & 31)) & 1) == 0) {
        return vec3(0.0);
    }
    float height = (float(LiteCameraBlock.y) + blockLocal.y) * 16.0;
    for (int i = 0; i < LITE_SWING_MAX_COLUMNS; i++) {
        if (i >= count) {
            break;
        }
        ivec4 column = LiteSwingColumns[i];
        if (column.x != block.x || column.y != block.y) {
            continue;
        }
        // Half a sixteenth inside the ends: the block under a lantern and the one a column hangs from stay put.
        if (height > float(column.z >> 12) + 0.5 && height < float(column.w >> 5) - 0.5) {
            return lite_swing_turn(position, column);
        }
    }
    return vec3(0.0);
}

#endif
