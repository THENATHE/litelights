package com.thenathe.litelights.overlays.generation;

/**
 * The 17 tiles of an OptiFine-format {@code method=overlay} set, in the order Continuity reads
 * them (see its StandardOverlayQuadProcessor). Edge letters are in texture space: L is the left
 * column, D the bottom row, R the right column and U the top row. Tiles 0, 2, 14 and 16 are
 * Continuity's corner-only pieces, for when just the diagonal neighbour is the softer block; the
 * generator leaves them empty ({@code <skip>}), since diagonal-only corners were dropped in 0.1.7.
 */
public final class TileLayout {
    public static final int LEFT = 1;
    public static final int DOWN = 2;
    public static final int RIGHT = 4;
    public static final int UP = 8;

    public static final int TILE_COUNT = 17;

    /** Edges each tile covers. Corner-only tiles have none. */
    private static final int[] EDGES = {
        0, DOWN, 0, DOWN | RIGHT, LEFT | DOWN, LEFT | DOWN | RIGHT, LEFT | DOWN | UP, RIGHT,
        LEFT | DOWN | RIGHT | UP, LEFT, RIGHT | UP, LEFT | UP, DOWN | RIGHT | UP, LEFT | RIGHT | UP,
        0, UP, 0
    };

    /** For corner-only tiles, the two edges whose corner it fills. */
    private static final int[] CORNERS = {
        DOWN | RIGHT, 0, LEFT | DOWN, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, RIGHT | UP, 0, LEFT | UP
    };

    private TileLayout() {
    }

    public static int edges(int tile) {
        return EDGES[tile];
    }

    public static int corner(int tile) {
        return CORNERS[tile];
    }
}
