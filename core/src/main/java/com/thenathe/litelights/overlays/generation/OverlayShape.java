package com.thenathe.litelights.overlays.generation;

/**
 * Decides which texels of a source's overlay pieces are covered: {@link EdgeShape} for the drawn
 * edge styles, {@link BrickShape} for masonry cut along its mortar lines. Masks hold one
 * {@link EdgeShape#EMPTY}, {@link EdgeShape#FROM_SIDE} or {@link EdgeShape#FROM_END} per texel.
 */
public interface OverlayShape {
    /** The texture size the masks are drawn at. */
    int size();

    /** Whether this draws one source's half of a blended seam rather than a full overlay. */
    boolean isBlend();

    /** One of the 17 tiles of a plain {@code method=overlay} set, as {@link TileLayout} numbers them. */
    byte[] cover(int tile);

    /** One edge's band, for a face whose corners at either end of that edge have the given classes. */
    byte[] band(int direction, int startClass, int endClass, int wave, int classes);

    /** The fill where two bands meet inside a face, at a corner of the given class. */
    byte[] innerCorner(int corner, int cornerClass, int classes);
}
