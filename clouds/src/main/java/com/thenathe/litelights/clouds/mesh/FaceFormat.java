package com.thenathe.litelights.clouds.mesh;

/**
 * The new clouds' face format: one cube face in four 32-bit words, read by the cloud vertex shader
 * from a texel buffer of four-channel unsigned integers ({@code shaders/clouds/dynamic.vsh} unpacks
 * the same bits; keep the two in step). A draw expands each face into a quad, four vertices per
 * face, with no vertex buffer.
 *
 * <p>Word 0, where the face is:
 * <pre>
 *   bits  0..9   x, signed, in cubes of the face's size from the generation's origin (chunk-local
 *                in a {@link FaceList} until the build joins the chunks)
 *   bits 10..19  z, the same
 *   bits 20..27  y, in cells of {@link #CELL_BLOCKS} above the floor (the lowest cloud base)
 *   bits 28..30  the side the face looks toward (0 down, 1 up, 2 north, 3 south, 4 west, 5 east)
 *   bit  31      unused
 * </pre>
 * Word 1, how it changes:
 * <pre>
 *   bits  0..1   the cube's width: 8 blocks shifted left by this (8, 16, 32, 64); every cube is one
 *                cell tall whatever its width
 *   bits  2..3   its state: stable, appearing or disappearing
 *   bits  4..6   for a cube that changes, the side it grows from (0: its middle), as for the face
 *                side plus one
 *   bit   7      it also widens from its middle across that side's axis
 *   bits  8..15  when in the change its growth starts, in 255ths of the change
 *   bits 16..23  when it ends, the same
 *   bits 24..27  how stormy its cloud is, 0..15
 *   bits 28..29  whether its whole chunk fades in or out over the change (a chunk coming into
 *                reach or changing cube size)
 *   bits 30..31  unused
 * </pre>
 * Words 2 and 3, how it is shaded at each of its four corners (in the order the shader walks
 * them, {@link #CORNERS}), so the shading runs on smoothly from face to face whatever the cubes do:
 * <pre>
 *   word 2 bits  0..9   corner 0's shading normal, two 5-bit hemi-octahedral coordinates ({@link #normal})
 *          bits 10..19  corner 1's
 *          bits 20..29  corner 2's
 *          bits 30..31  unused
 *   word 3 bits  0..9   corner 3's
 *          bits 10..19  how high the cloud reaches over the face's first edge (its corners at the low
 *                       end of x, or of z for west and east faces), in blocks above the floor, halved
 *          bits 20..29  the same over its other edge
 *          bits 30..31  how many cells tall the face is, less one: the side faces of stable cubes
 *                       stacked in a column are joined, up to {@link #MOST_STACK}
 * </pre>
 * The shader darkens each corner by how far under that top it lies, so the darkening is exact
 * up and down a wall and shared with the faces beside it.
 *
 * Positions are in cubes rather than blocks so bigger cubes far away reach as far as their size
 * (10 bits of 8-block cubes is 4 km each way, of 64-block cubes 32 km). Heights stay in 8-block
 * cells at every width, which reach 2,048 blocks above the floor.
 */
public final class FaceFormat {
    public static final int WORDS = 4;
    public static final int BYTES = WORDS * 4;
    public static final int CELL_BLOCKS = 8;
    /** The furthest a face may be from the origin, in cubes, either way. */
    public static final int MOST_OFFSET = 511;
    public static final int MOST_HEIGHT_CELLS = 255;
    /** The tallest a joined face may be, in cells. */
    public static final int MOST_STACK = 4;
    /** The highest cloud top word 3 can hold, in blocks above the floor. */
    public static final float MOST_TOP = 1023 * 2.0f;
    /** Bits in each of a normal's two octahedral coordinates. */
    private static final int NORMAL_BITS = 5;
    /** Codes 0..30, so the axes (0 at the middle) are exact. */
    private static final int NORMAL_STEPS = 30;

    /**
     * Each side's corners in the unit cube, in the order the shader draws them (wound
     * counter-clockwise seen from outside): down, up, north, south, west, east. The same table as
     * {@code CORNERS} in {@code shaders/clouds/dynamic.vsh}.
     */
    public static final int[][] CORNERS = {
        {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1},
        {0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0},
        {0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0},
        {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1},
        {0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0},
        {1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}
    };

    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    public static final int STABLE = 0;
    public static final int APPEARING = 1;
    public static final int DISAPPEARING = 2;

    public static final int FADE_NONE = 0;
    public static final int FADE_IN = 1;
    public static final int FADE_OUT = 2;

    /** No side to grow from: the cube grows from its middle. */
    public static final int FROM_MIDDLE = 0;

    public static final int WINDOW_STEPS = 255;
    public static final int MOST_SHADE = 15;

    private FaceFormat() {
    }

    public static int position(int x, int z, int y, int side) {
        if (x < -MOST_OFFSET || x > MOST_OFFSET || z < -MOST_OFFSET || z > MOST_OFFSET) {
            throw new IllegalArgumentException("face too far from the origin: " + x + ", " + z);
        }
        if (y < 0 || y > MOST_HEIGHT_CELLS) {
            throw new IllegalArgumentException("face height out of range: " + y);
        }
        return (x & 0x3FF) | (z & 0x3FF) << 10 | y << 20 | side << 28;
    }

    /** The same face moved by whole cubes along x and z (a chunk's faces placed in the generation). */
    public static int moved(int position, int dx, int dz) {
        return position(x(position) + dx, z(position) + dz, y(position), side(position));
    }

    /**
     * @param growFrom    the side the cube grows from ({@link #DOWN} and so on), or -1 for its middle
     * @param widens      whether it also widens from its middle across that side's axis
     * @param windowStart when its growth starts in the change, 0..1
     * @param windowEnd   when its growth ends, 0..1, at least {@code windowStart}
     * @param storm       how stormy, 0..{@link #MOST_SHADE}
     * @param fade        {@link #FADE_NONE}, {@link #FADE_IN} or {@link #FADE_OUT}
     */
    public static int look(int sizeShift, int state, int growFrom, boolean widens, float windowStart, float windowEnd, int storm, int fade) {
        int start = Math.round(Math.clamp(windowStart, 0.0f, 1.0f) * WINDOW_STEPS);
        int end = Math.max(start, Math.round(Math.clamp(windowEnd, 0.0f, 1.0f) * WINDOW_STEPS));
        return sizeShift & 3
            | (state & 3) << 2
            | (growFrom + 1 & 7) << 4
            | (widens ? 1 : 0) << 7
            | start << 8
            | end << 16
            | Math.clamp(storm, 0, MOST_SHADE) << 24
            | (fade & 3) << 28;
    }

    /** The same look fading in or out with its chunk. */
    public static int faded(int look, int fade) {
        return look & ~(3 << 28) | (fade & 3) << 28;
    }

    /**
     * Words 2 and 3 from the corners' shading.
     *
     * @param normals the corners' {@link #normal} codes, in {@link #CORNERS} order
     * @param lowTop  how high the cloud reaches over the face's first edge, blocks above the floor
     * @param highTop the same over its other edge
     * @param cells   how many cells tall the face is, 1..{@link #MOST_STACK}
     */
    public static int shadeWord2(int[] normals) {
        return normals[0] | normals[1] << 10 | normals[2] << 20;
    }

    public static int shadeWord3(int[] normals, float lowTop, float highTop, int cells) {
        return normals[3]
            | topCode(lowTop) << 10
            | topCode(highTop) << 20
            | (Math.clamp(cells, 1, MOST_STACK) - 1) << 30;
    }

    private static int topCode(float top) {
        return Math.round(Math.clamp(top, 0.0f, MOST_TOP) / 2.0f);
    }

    /** Which of a face's two edges a corner is on: 0 the first (low x, or low z for west and east faces), 1 the other. */
    public static int edge(int side, int corner) {
        int[] offset = CORNERS[side * 4 + corner];
        return side >= WEST ? offset[2] : offset[0];
    }

    /** One corner's normal code, from words 2 and 3. */
    public static int cornerNormal(int word2, int word3, int corner) {
        return corner < 3 ? word2 >>> (10 * corner) & 0x3FF : word3 & 0x3FF;
    }

    /** How high the cloud reaches over one of the face's edges, in blocks above the floor, from word 3. */
    public static float edgeTop(int word3, int edge) {
        return (word3 >>> (10 + 10 * edge) & 0x3FF) * 2.0f;
    }

    /** How many cells tall the face is, from word 3. */
    public static int cells(int word3) {
        return (word3 >>> 30) + 1;
    }

    public static int x(int position) {
        return position << 22 >> 22;
    }

    public static int z(int position) {
        return position << 12 >> 22;
    }

    public static int y(int position) {
        return position >>> 20 & 0xFF;
    }

    public static int side(int position) {
        return position >>> 28 & 7;
    }

    public static int sizeShift(int look) {
        return look & 3;
    }

    public static int state(int look) {
        return look >>> 2 & 3;
    }

    /** The side the cube grows from, or -1 for its middle. */
    public static int growFrom(int look) {
        return (look >>> 4 & 7) - 1;
    }

    public static boolean widens(int look) {
        return (look >>> 7 & 1) != 0;
    }

    public static float windowStart(int look) {
        return (look >>> 8 & 0xFF) / (float) WINDOW_STEPS;
    }

    public static float windowEnd(int look) {
        return (look >>> 16 & 0xFF) / (float) WINDOW_STEPS;
    }

    public static int storm(int look) {
        return look >>> 24 & 0xF;
    }

    public static int fade(int look) {
        return look >>> 28 & 3;
    }

    /** The side opposite {@code side}. */
    public static int opposite(int side) {
        return side ^ 1;
    }

    /**
     * A direction as 10 bits, in the frame of the face it shades: the half of all directions facing
     * out of the face's side, hemi-octahedral (scaled to |a| + |b| + c = 1 along the face's two
     * edges and its outward normal, then turned 45 degrees to fill a square), 5 bits each, within
     * about 3 degrees. A direction facing into the face is laid flat along it. Any length but zero.
     */
    public static int normal(int side, double x, double y, double z) {
        // The direction along the face's first edge (x, or z for west and east faces), its second
        // (z for tops and bottoms, y for the sides) and out of it.
        double alongA = side >= WEST ? z : x;
        double alongB = side <= UP ? z : y;
        double out = Math.max(axisX(side) * x + axisY(side) * y + axisZ(side) * z, 0.0);
        double length = Math.abs(alongA) + Math.abs(alongB) + out;
        if (length <= 0.0) {
            return NORMAL_STEPS / 2 | (NORMAL_STEPS / 2) << NORMAL_BITS;
        }
        double a = alongA / length;
        double b = alongB / length;
        int codeU = (int) Math.round(((a + b) * 0.5 + 0.5) * NORMAL_STEPS);
        int codeV = (int) Math.round(((a - b) * 0.5 + 0.5) * NORMAL_STEPS);
        return Math.clamp(codeU, 0, NORMAL_STEPS) | Math.clamp(codeV, 0, NORMAL_STEPS) << NORMAL_BITS;
    }

    /** A {@link #normal} code back as a unit vector {x, y, z} in the world, as the shader reads it. */
    public static float[] direction(int side, int code) {
        float u = (code & 31) / (float) NORMAL_STEPS * 2.0f - 1.0f;
        float v = (code >>> NORMAL_BITS & 31) / (float) NORMAL_STEPS * 2.0f - 1.0f;
        float a = (u + v) * 0.5f;
        float b = (u - v) * 0.5f;
        float out = Math.max(0.0f, 1.0f - Math.abs(a) - Math.abs(b));
        double[] edgeA = edgeA(side);
        double[] edgeB = edgeB(side);
        float x = (float) (edgeA[0] * a + edgeB[0] * b + axisX(side) * out);
        float y = (float) (edgeA[1] * a + edgeB[1] * b + axisY(side) * out);
        float z = (float) (edgeA[2] * a + edgeB[2] * b + axisZ(side) * out);
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[] {x / length, y / length, z / length};
    }

    /** A face's first edge: x for tops, bottoms, north and south faces; z for west and east. */
    private static double[] edgeA(int side) {
        return side >= WEST ? new double[] {0.0, 0.0, 1.0} : new double[] {1.0, 0.0, 0.0};
    }

    /** A face's second edge: z for tops and bottoms, y for the sides. */
    private static double[] edgeB(int side) {
        return side <= UP ? new double[] {0.0, 0.0, 1.0} : new double[] {0.0, 1.0, 0.0};
    }

    private static int axisX(int side) {
        return side == WEST ? -1 : side == EAST ? 1 : 0;
    }

    private static int axisY(int side) {
        return side == DOWN ? -1 : side == UP ? 1 : 0;
    }

    private static int axisZ(int side) {
        return side == NORTH ? -1 : side == SOUTH ? 1 : 0;
    }
}
