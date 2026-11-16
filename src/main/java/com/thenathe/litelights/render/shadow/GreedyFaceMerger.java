package com.thenathe.litelights.render.shadow;

import java.util.function.IntConsumer;

/**
 * Merges a 16 by 16 grid of full block faces into as few rectangles as it can (greedy meshing).
 * Shadow casters for a flat field of stone become one quad per slice instead of 256.
 *
 * <p>Cells are indexed {@code u + v * 16}. Each rectangle is reported as one packed int:
 * {@code u | v << 4 | (width - 1) << 8 | (height - 1) << 12}.
 */
public final class GreedyFaceMerger {
    public static final int SIZE = 16;

    private GreedyFaceMerger() {
    }

    /**
     * Consumes {@code mask} (cells that need a face) and reports rectangles covering exactly those
     * cells. The mask is cleared as cells are covered.
     */
    public static void merge(boolean[] mask, IntConsumer rectangles) {
        for (int v = 0; v < SIZE; v++) {
            for (int u = 0; u < SIZE; u++) {
                if (!mask[u + v * SIZE]) {
                    continue;
                }
                int width = 1;
                while (u + width < SIZE && mask[u + width + v * SIZE]) {
                    width++;
                }
                int height = 1;
                while (v + height < SIZE && rowIsFilled(mask, u, v + height, width)) {
                    height++;
                }
                for (int row = v; row < v + height; row++) {
                    for (int column = u; column < u + width; column++) {
                        mask[column + row * SIZE] = false;
                    }
                }
                rectangles.accept(pack(u, v, width, height));
            }
        }
    }

    private static boolean rowIsFilled(boolean[] mask, int u, int v, int width) {
        for (int column = u; column < u + width; column++) {
            if (!mask[column + v * SIZE]) {
                return false;
            }
        }
        return true;
    }

    public static int pack(int u, int v, int width, int height) {
        return u | v << 4 | (width - 1) << 8 | (height - 1) << 12;
    }

    public static int u(int rectangle) {
        return rectangle & 15;
    }

    public static int v(int rectangle) {
        return rectangle >> 4 & 15;
    }

    public static int width(int rectangle) {
        return (rectangle >> 8 & 15) + 1;
    }

    public static int height(int rectangle) {
        return (rectangle >> 12 & 15) + 1;
    }
}
