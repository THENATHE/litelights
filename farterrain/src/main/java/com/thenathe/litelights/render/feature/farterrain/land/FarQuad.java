package com.thenathe.litelights.render.feature.farterrain.land;

/**
 * One far terrain quad: four 32-bit words, one RGBA32_UINT texel of the quad buffer, which the
 * vertex shader turns into six vertices (shaders/farterrain/far_land.vsh mirrors this layout).
 *
 * <pre>
 * word 0  x (5 bits) | z (5) << 5 | y (10) << 10 | face (3) << 20 | block light (4) << 23 | sky light (4) << 27 | detail (1) << 31
 * word 1  first extent - 1 (5 bits) | second extent - 1 (10) << 5 | inset (8) << 15 | water depth class (4) << 23
 *         or, for a detail quad: first extent - 1 (5) | second extent - 1 (5) << 5 | a0 (4) << 10 | a1 - 1 (4) << 14
 *         | b0 (4) << 18 | b1 - 1 (4) << 22 | depth (4) << 26
 * word 2  appearance (16 bits) | node index, low 16 bits << 16
 * word 3  tint 0xRRGGBB (24 bits) | node index, high 8 bits << 24
 * </pre>
 *
 * x, y and z are the voxel of the rectangle's lowest corner within the tile. The extents run along
 * the face's two axes: x then z for up and down faces, x then y for north and south, z then y for
 * west and east. Faces are numbered as Minecraft's Direction: down, up, north, south, west, east;
 * 6 and 7 are a plant's two crossed cards, corner to corner across the block (6 from its
 * north-west corner, 7 from its south-west). The inset lowers the quad's top edge (all of an up
 * face) by that many blocks: a coarse voxel's land ends below its top ({@link TileVoxels#inset}).
 *
 * <p>A light point ({@link FarMesh#LIGHTS}) is a quad of its own kind, told apart by its bucket:
 * the voxel of its brightest light, the light's strength (0 to 255) in the light byte and its
 * colour in the tint.
 *
 * <p>A detail quad (level 0 only, so it never needs an inset) is part of a block's face: in each
 * block it covers a0 to a1 sixteenths along the first axis and b0 to b1 along the second, on a
 * plane {@code depth} sixteenths in from the block's own face (a bottom slab's top is 8 in). A
 * detail quad spans more than one block only along an axis it covers whole.
 */
public final class FarQuad {
    public static final int INTS = 4;
    public static final int BYTES = INTS * 4;

    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;
    /** A plant's card from the block's north-west corner to its south-east; {@link FaceLooks} gives the card's look here. */
    public static final int CARD = 6;
    /** The other card, from the south-west corner to the north-east. */
    public static final int CARD_CROSSED = 7;

    /** The largest second extent and voxel row the layout holds. */
    public static final int MAX_ROWS = 1024;
    /** The largest extent of a detail quad, along either axis. */
    public static final int MAX_DETAIL_EXTENT = 32;
    public static final int MAX_NODES = 1 << 24;

    private static final int DETAIL = 1 << 31;

    private FarQuad() {
    }

    /**
     * The water depths a water top face's class stands for, in blocks (far_land.fsh mirrors them):
     * class 0 is a face with no depth given, taken as deep. Steps widen with depth, where the
     * water's colour changes less, so faces over a sloping floor still merge.
     */
    public static final int[] WATER_DEPTHS = {64, 1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 48, 64};

    /** The class of the depth nearest {@code blocks} of water, from 1 up. */
    public static int depthClass(int blocks) {
        for (int depthClass = 1; depthClass < WATER_DEPTHS.length; depthClass++) {
            if (WATER_DEPTHS[depthClass] >= blocks) {
                int below = WATER_DEPTHS[depthClass - 1];
                return depthClass > 1 && blocks - below < WATER_DEPTHS[depthClass] - blocks ? depthClass - 1 : depthClass;
            }
        }
        return WATER_DEPTHS.length - 1;
    }

    public static void write(int[] into, int offset, int x, int y, int z, int face, int extentA, int extentB, int light, int appearance, int tint,
                             int inset) {
        write(into, offset, x, y, z, face, extentA, extentB, light, appearance, tint, inset, 0);
    }

    /** As above, with a water top face's depth class ({@link #depthClass}). */
    public static void write(int[] into, int offset, int x, int y, int z, int face, int extentA, int extentB, int light, int appearance, int tint,
                             int inset, int depthClass) {
        into[offset] = x | z << 5 | y << 10 | face << 20 | (light & 0xFF) << 23;
        into[offset + 1] = (extentA - 1) | (extentB - 1) << 5 | (inset & 0xFF) << 15 | (depthClass & 0xF) << 23;
        into[offset + 2] = appearance & 0xFFFF;
        into[offset + 3] = tint & 0xFFFFFF;
    }

    /**
     * Writes a detail quad. {@code part} is the face's place within each block, as
     * {@link #detailPart} packs it.
     */
    public static void writeDetail(int[] into, int offset, int x, int y, int z, int face, int extentA, int extentB, int light, int appearance,
                                   int tint, int part) {
        into[offset] = x | z << 5 | y << 10 | face << 20 | (light & 0xFF) << 23 | DETAIL;
        into[offset + 1] = (extentA - 1) | (extentB - 1) << 5 | part << 10;
        into[offset + 2] = appearance & 0xFFFF;
        into[offset + 3] = tint & 0xFFFFFF;
    }

    /** Writes a light point: a glow at the voxel (x, y, z) of this strength (0 to 255) and colour. */
    public static void writeLight(int[] into, int offset, int x, int y, int z, int strength, int colour) {
        write(into, offset, x, y, z, UP, 1, 1, Math.min(strength, 255), 0, colour, 0);
    }

    /** A light point's strength, 0 to 255. */
    public static int lightStrength(int[] quads, int offset) {
        return (quads[offset] >>> 23) & 0xFF;
    }

    /** A detail quad's place within a block: a0 to a1 and b0 to b1 sixteenths, {@code depth} in from the block's face. */
    public static int detailPart(int a0, int a1, int b0, int b1, int depth) {
        return a0 | (a1 - 1) << 4 | b0 << 8 | (b1 - 1) << 12 | depth << 16;
    }

    /** Whether a part covers the whole block along the first axis (so it may merge along it). */
    static boolean partSpansA(int part) {
        return (part & 15) == 0 && (part >>> 4 & 15) == 15;
    }

    /** Whether a part covers the whole block along the second axis. */
    static boolean partSpansB(int part) {
        return (part >>> 8 & 15) == 0 && (part >>> 12 & 15) == 15;
    }

    /** Puts the node index into a quad already written (when its mesh finds its place on the GPU). */
    public static void setNode(int[] quads, int offset, int node) {
        quads[offset + 2] = (quads[offset + 2] & 0xFFFF) | (node & 0xFFFF) << 16;
        quads[offset + 3] = (quads[offset + 3] & 0xFFFFFF) | (node >>> 16) << 24;
    }

    public static int x(int[] quads, int offset) {
        return quads[offset] & 31;
    }

    public static int z(int[] quads, int offset) {
        return (quads[offset] >>> 5) & 31;
    }

    public static int y(int[] quads, int offset) {
        return (quads[offset] >>> 10) & 1023;
    }

    public static int face(int[] quads, int offset) {
        return (quads[offset] >>> 20) & 7;
    }

    public static int skyLight(int[] quads, int offset) {
        return (quads[offset] >>> 27) & 15;
    }

    public static int blockLight(int[] quads, int offset) {
        return (quads[offset] >>> 23) & 15;
    }

    public static boolean detail(int[] quads, int offset) {
        return (quads[offset] & DETAIL) != 0;
    }

    public static int extentA(int[] quads, int offset) {
        return (quads[offset + 1] & 31) + 1;
    }

    public static int extentB(int[] quads, int offset) {
        int mask = detail(quads, offset) ? 31 : 1023;
        return ((quads[offset + 1] >>> 5) & mask) + 1;
    }

    public static int inset(int[] quads, int offset) {
        return detail(quads, offset) ? 0 : (quads[offset + 1] >>> 15) & 0xFF;
    }

    /** A detail quad's part ({@link #detailPart}); 0 for a whole face. */
    public static int part(int[] quads, int offset) {
        return detail(quads, offset) ? quads[offset + 1] >>> 10 : 0;
    }

    public static int appearance(int[] quads, int offset) {
        return quads[offset + 2] & 0xFFFF;
    }

    public static int tint(int[] quads, int offset) {
        return quads[offset + 3] & 0xFFFFFF;
    }

    public static int node(int[] quads, int offset) {
        return (quads[offset + 2] >>> 16) | (quads[offset + 3] >>> 24) << 16;
    }
}
