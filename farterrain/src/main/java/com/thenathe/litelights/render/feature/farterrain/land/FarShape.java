package com.thenathe.litelights.render.feature.farterrain.land;

import java.util.Arrays;

/**
 * How a block is drawn at full detail (level 0), from the game's own shape and model:
 * <ul>
 *   <li>{@link #FULL}: a whole voxel (full blocks, leaves, lava, and the block a cover was folded onto);</li>
 *   <li>{@link #BOXES}: up to {@link #MAX_BOXES} boxes in sixteenths of a block, the largest of its
 *   shape (slabs, stairs, paths, fences, walls, panes, doors, torches, lanterns, chests);</li>
 *   <li>{@link #CARDS}: two crossed cards, as the game draws plants;</li>
 *   <li>{@link #NONE}: nothing (air, invisible blocks, things too small to see far away).</li>
 * </ul>
 * Coarser levels draw whole voxels only ({@link LevelBuilder}), so none of this reaches them.
 *
 * @param boxes     per box six values, x0 y0 z0 x1 y1 z1 in sixteenths (0 to 16)
 * @param fullSides bit per face direction (FarQuad order) whose side of the cell the shape covers,
 *                  so the neighbour's face against it is left out
 */
public record FarShape(int type, int[] boxes, int fullSides) {
    public static final int NONE = 0;
    public static final int FULL = 1;
    public static final int BOXES = 2;
    public static final int CARDS = 3;

    /** The most boxes a shape keeps: an all-sides fence or wall is a post and four arms. */
    public static final int MAX_BOXES = 6;
    /** Boxes smaller than this many cubic sixteenths in all are left out (buttons, levers). */
    static final int SMALLEST_VOLUME = 4 * 4 * 4;
    /** A side counts as covered when the shape covers this share of it, in 256ths. */
    private static final int COVERED_SIDE = 224;

    public static final FarShape EMPTY = new FarShape(NONE, new int[0], 0);
    public static final FarShape WHOLE = new FarShape(FULL, new int[0], 0b111111);
    public static final FarShape CROSS = new FarShape(CARDS, new int[0], 0);

    public int boxCount() {
        return boxes.length / 6;
    }

    public boolean coversSide(int face) {
        return (fullSides >> face & 1) != 0;
    }

    /**
     * A shape of these boxes (six values each, sixteenths): the largest {@link #MAX_BOXES} by
     * volume, {@link #EMPTY} when what is left is too small to see.
     */
    public static FarShape ofBoxes(int[] boxes) {
        int count = boxes.length / 6;
        Integer[] order = new Integer[count];
        int volume = 0;
        for (int i = 0; i < count; i++) {
            order[i] = i;
            volume += volume(boxes, i);
        }
        if (count == 0 || volume < SMALLEST_VOLUME) {
            return EMPTY;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(volume(boxes, b), volume(boxes, a)));
        int kept = Math.min(count, MAX_BOXES);
        int[] largest = new int[kept * 6];
        for (int i = 0; i < kept; i++) {
            System.arraycopy(boxes, order[i] * 6, largest, i * 6, 6);
        }
        return new FarShape(BOXES, largest, coveredSides(largest));
    }

    private static int volume(int[] boxes, int box) {
        int at = box * 6;
        return (boxes[at + 3] - boxes[at]) * (boxes[at + 4] - boxes[at + 1]) * (boxes[at + 5] - boxes[at + 2]);
    }

    /** Which sides of the cell the boxes cover nearly whole (a path's 15/16 counts). */
    static int coveredSides(int[] boxes) {
        int sides = 0;
        for (int face = 0; face < 6; face++) {
            boolean[] covered = new boolean[256];
            int count = 0;
            for (int at = 0; at < boxes.length; at += 6) {
                if (!touchesSide(boxes, at, face)) {
                    continue;
                }
                // The box's extent across the side, as the two in-plane axes (FarQuad's a and b).
                int axisA = face == FarQuad.WEST || face == FarQuad.EAST ? 2 : 0;
                int axisB = face == FarQuad.DOWN || face == FarQuad.UP ? 2 : 1;
                for (int b = boxes[at + axisB]; b < boxes[at + axisB + 3]; b++) {
                    for (int a = boxes[at + axisA]; a < boxes[at + axisA + 3]; a++) {
                        if (!covered[a + b * 16]) {
                            covered[a + b * 16] = true;
                            count++;
                        }
                    }
                }
            }
            if (count >= COVERED_SIDE) {
                sides |= 1 << face;
            }
        }
        return sides;
    }

    /** Whether a box reaches the cell's side toward {@code face}. */
    static boolean touchesSide(int[] boxes, int at, int face) {
        return switch (face) {
            case FarQuad.DOWN -> boxes[at + 1] == 0;
            case FarQuad.UP -> boxes[at + 4] == 16;
            case FarQuad.NORTH -> boxes[at + 2] == 0;
            case FarQuad.SOUTH -> boxes[at + 5] == 16;
            case FarQuad.WEST -> boxes[at] == 0;
            default -> boxes[at + 3] == 16;
        };
    }
}
