package com.thenathe.litelights.overlays.generation;

/**
 * Cuts overlay tiles out of a source texture.
 *
 * <p>Left and right bands carry on the source texture where it left off, which is right for any
 * texture that repeats sideways (all block textures do). Top and bottom bands mirror it instead:
 * a grass block's side isn't meant to repeat upwards, and mirroring puts its bottom (dirt) rows
 * against a block below it and its top (grass) rows against a block above, which is what the
 * eye expects when a face continues over a step.
 *
 * <p>Step bands lie on the top of the lower block and continue the upper block's side face, so
 * they read the side texture upwards from its bottom row, starting at the edge they touch.
 */
public final class TileComposer {
    /** How a covered texel picks its source texel. */
    public enum Mapping {
        /** Bands on the face itself: {@link EdgeShape#FROM_SIDE} continues, {@link EdgeShape#FROM_END} mirrors. */
        FACE,
        STEP_LEFT,
        STEP_DOWN,
        STEP_RIGHT,
        STEP_UP;

        public static Mapping step(int direction) {
            return switch (direction) {
                case EdgeShape.LEFT -> STEP_LEFT;
                case EdgeShape.DOWN -> STEP_DOWN;
                case EdgeShape.RIGHT -> STEP_RIGHT;
                default -> STEP_UP;
            };
        }
    }

    private TileComposer() {
    }

    /**
     * @param source square ARGB texels of the source texture, row by row
     * @return one ARGB tile per entry of {@link TileLayout}, or {@code null} where a tile would be empty
     */
    public static int[][] compose(int[] source, int size, OverlayShape shape) {
        int[][] tiles = new int[TileLayout.TILE_COUNT][];
        for (int tile = 0; tile < TileLayout.TILE_COUNT; tile++) {
            tiles[tile] = compose(source, size, shape.cover(tile), Mapping.FACE);
        }
        return tiles;
    }

    /**
     * Fills a mask's covered texels from the source texture.
     *
     * @return ARGB texels row by row, or {@code null} if nothing is covered
     */
    public static int[] compose(int[] source, int size, byte[] mask, Mapping mapping) {
        int[] pixels = new int[size * size];
        boolean anyCovered = false;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                byte cover = mask[y * size + x];
                if (cover == EdgeShape.EMPTY) {
                    continue;
                }
                int texel = source[sourceIndex(mapping, cover, x, y, size)];
                // Overlays draw in the cutout layer, so every texel is fully opaque or fully clear.
                if ((texel >>> 24) >= 128) {
                    pixels[y * size + x] = texel | 0xFF000000;
                    anyCovered = true;
                }
            }
        }
        return anyCovered ? pixels : null;
    }

    private static int sourceIndex(Mapping mapping, byte cover, int x, int y, int size) {
        int last = size - 1;
        return switch (mapping) {
            case FACE -> (cover == EdgeShape.FROM_END ? last - y : y) * size + x;
            // Distance from the step's edge picks the row, counted up from the bottom; along the edge picks the column.
            case STEP_LEFT -> (last - x) * size + y;
            case STEP_RIGHT -> x * size + y;
            case STEP_UP -> (last - y) * size + x;
            case STEP_DOWN -> y * size + x;
        };
    }
}
