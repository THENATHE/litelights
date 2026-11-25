package com.thenathe.litelights.render.feature.farterrain.land;

/**
 * A tile's quads ({@link FarQuad}), in buckets drawn apart: one per face direction, so a draw can
 * leave out the directions that face away from the camera; the plants' cards, drawn only near
 * ({@link #CARDS}); the water ({@link #WATER}), drawn over the scene after everything opaque; and
 * its lights as points that glow at night ({@link #LIGHTS}, {@link FarQuad#writeLight}). Also the
 * rows its land spans, for culling.
 *
 * @param quads      {@link FarQuad#INTS} ints per quad, the buckets one after another
 * @param firstQuad  per bucket, the index of its first quad
 * @param quadCount  per bucket, how many quads it has
 * @param lowestRow  the lowest voxel row with a face, or 0 when there is none
 * @param highestRow the highest voxel row with a face
 */
public record FarMesh(int[] quads, int[] firstQuad, int[] quadCount, int lowestRow, int highestRow) {
    /** Buckets 0 to 5 are the face directions (FarQuad order). */
    public static final int CARDS = 6;
    public static final int WATER = 7;
    public static final int LIGHTS = 8;
    public static final int BUCKETS = 9;

    public int totalQuads() {
        return quads.length / FarQuad.INTS;
    }

    public boolean isEmpty() {
        return quads.length == 0;
    }
}
