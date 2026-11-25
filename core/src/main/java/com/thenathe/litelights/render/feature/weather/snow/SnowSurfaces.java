package com.thenathe.litelights.render.feature.weather.snow;

import java.util.ArrayList;
import java.util.List;

/**
 * The upward surfaces of a block's shape that fake snow lies on, worked out once per block state
 * from the game's own shape of it: snow follows the block, so on stairs it lies on each step at the
 * step's height, on a bottom slab on the slab's top, and on a wall, a fence or a pane along its top.
 *
 * <p>The shape is read on a grid of sixteenths, the size of a texel: for each sixteenth of the
 * block's footprint the height of the shape's highest point there ({@link #heights}), and the
 * footprint is cut into rectangles of one height ({@link Top}), greedily, row by row. Columns the
 * shape leaves empty hold no snow here (snow on the ground around a fence post is the snow at the
 * block's base, a separate rule). No Minecraft types here, so it is unit-tested on its own.
 */
public final class SnowSurfaces {
    /** Sixteenths a block. */
    public static final int GRID = 16;
    public static final SnowSurfaces NONE = new SnowSurfaces(new int[GRID * GRID], List.of());

    /**
     * A rectangle of the footprint whose shape tops out at one height, in sixteenths: x from
     * {@code minX} to {@code maxX}, z from {@code minZ} to {@code maxZ} (ends excluded), at {@code top}.
     */
    public record Top(int minX, int minZ, int maxX, int maxZ, int top) {
    }

    private final int[] heights;
    private final List<Top> tops;

    private SnowSurfaces(int[] heights, List<Top> tops) {
        this.heights = heights;
        this.tops = List.copyOf(tops);
    }

    /**
     * The surfaces of a shape given as boxes in blocks (minX, minY, minZ, maxX, maxY, maxZ, six to a
     * box), as {@code VoxelShape.forAllBoxes} hands them out. Parts of boxes outside the block's own
     * footprint are left out.
     */
    public static SnowSurfaces ofBoxes(double[] boxes) {
        int[] heights = new int[GRID * GRID];
        for (int box = 0; box + 5 < boxes.length; box += 6) {
            int minX = sixteenths(boxes[box]);
            int minZ = sixteenths(boxes[box + 2]);
            int maxX = sixteenths(boxes[box + 3]);
            int top = sixteenths(boxes[box + 4]);
            int maxZ = sixteenths(boxes[box + 5]);
            for (int z = Math.max(0, minZ); z < Math.min(GRID, maxZ); z++) {
                for (int x = Math.max(0, minX); x < Math.min(GRID, maxX); x++) {
                    heights[z * GRID + x] = Math.max(heights[z * GRID + x], top);
                }
            }
        }
        return new SnowSurfaces(heights, rectangles(heights));
    }

    private static int sixteenths(double blocks) {
        return (int) Math.round(blocks * GRID);
    }

    /** Greedy rectangles of equal height, row by row: each grows along x, then down z while the rows match. */
    private static List<Top> rectangles(int[] heights) {
        boolean[] taken = new boolean[GRID * GRID];
        List<Top> tops = new ArrayList<>();
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int height = heights[z * GRID + x];
                if (height <= 0 || taken[z * GRID + x]) {
                    continue;
                }
                int endX = x + 1;
                while (endX < GRID && !taken[z * GRID + endX] && heights[z * GRID + endX] == height) {
                    endX++;
                }
                int endZ = z + 1;
                while (endZ < GRID && rowMatches(heights, taken, endZ, x, endX, height)) {
                    endZ++;
                }
                for (int fillZ = z; fillZ < endZ; fillZ++) {
                    for (int fillX = x; fillX < endX; fillX++) {
                        taken[fillZ * GRID + fillX] = true;
                    }
                }
                tops.add(new Top(x, z, endX, endZ, height));
            }
        }
        return tops;
    }

    private static boolean rowMatches(int[] heights, boolean[] taken, int z, int fromX, int toX, int height) {
        for (int x = fromX; x < toX; x++) {
            if (taken[z * GRID + x] || heights[z * GRID + x] != height) {
                return false;
            }
        }
        return true;
    }

    public List<Top> tops() {
        return tops;
    }

    public boolean isEmpty() {
        return tops.isEmpty();
    }

    /**
     * The shape's height at a sixteenth of the footprint, 0 where it has none; outside the footprint
     * 0, as the block beside is another block.
     */
    public int heightAt(int x, int z) {
        if (x < 0 || z < 0 || x >= GRID || z >= GRID) {
            return 0;
        }
        return heights[z * GRID + x];
    }

    /** Whether the whole footprint tops out at the block's top, so real snow could lie on it. */
    public boolean fullTop() {
        return tops.size() == 1 && tops.get(0).equals(new Top(0, 0, GRID, GRID, GRID));
    }

    /** The highest point of the shape, in sixteenths. */
    public int highest() {
        int highest = 0;
        for (Top top : tops) {
            highest = Math.max(highest, top.top());
        }
        return highest;
    }

    int[] heights() {
        return heights.clone();
    }
}
