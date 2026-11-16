package com.thenathe.litelights.render.shadow;

/**
 * One section's shadow caster geometry, as built on a worker thread: quads of 4 vertices, each
 * {@link #VERTEX_SIZE} bytes. Positions are section-local in 1/{@link #POSITION_SCALE} block units,
 * offset by {@link #POSITION_OFFSET} blocks so model parts that stick out of their block stay
 * positive. The fourth position component holds {@link #FLAG_ALPHA_TEST}.
 *
 * <p>The hash covers every byte of the geometry. Two builds of a section with the same hash cast the
 * same shadow, which is how light-only rebuilds are told apart from real changes.
 *
 * <p>{@code vertices} comes from a {@link CasterArrayPool} and is usually longer than the geometry:
 * only the first {@link #byteCount} bytes belong to the mesh.
 */
public record CasterMesh(long sectionKey, byte[] vertices, int quadCount, long hash) {
    public static final int VERTEX_SIZE = 12;
    public static final int POSITION_SCALE = 64;
    public static final float POSITION_OFFSET = 8.0f;
    public static final int FLAG_ALPHA_TEST = 1;
    public static final int QUAD_SIZE = VERTEX_SIZE * 4;

    private static final byte[] NO_VERTICES = new byte[0];

    public static CasterMesh empty(long sectionKey) {
        return new CasterMesh(sectionKey, NO_VERTICES, 0, 0L);
    }

    public boolean isEmpty() {
        return quadCount == 0;
    }

    /** How many bytes of {@link #vertices} hold the geometry. */
    public int byteCount() {
        return quadCount * QUAD_SIZE;
    }
}
