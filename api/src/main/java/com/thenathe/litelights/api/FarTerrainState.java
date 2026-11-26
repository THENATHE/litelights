package com.thenathe.litelights.api;

/**
 * What the far terrain module tells the rest of LiteLights about the frame. Provided by the far
 * terrain module once it starts ({@link LiteLightsApi#provideFarTerrain}).
 */
public interface FarTerrainState {
    /** No far terrain module: nothing is drawn past the render distance. */
    FarTerrainState NONE = new FarTerrainState() {
        @Override
        public boolean farTerrainDrawn() {
            return false;
        }

        @Override
        public float farDistance() {
            return 0.0f;
        }
    };

    /**
     * True while far terrain is drawn past the render distance and LiteLights shades it, so the
     * land no longer ends there (the fog's render-distance fade moves out). Asked during a frame
     * update before far terrain's own has run, it answers from the previous frame.
     */
    boolean farTerrainDrawn();

    /** How far the far terrain reaches, in blocks, while {@link #farTerrainDrawn}; 0 otherwise. */
    float farDistance();

    /**
     * The highest land (or water surface) far terrain knows of within a rectangle of block columns,
     * at least as high as any of it and coarse (whole tiles of a few hundred blocks), or
     * {@link Integer#MIN_VALUE} where it knows none of it or isn't drawn. For placing things over
     * distant land without putting them inside it (the distant fireworks). Client thread.
     */
    default int highestLand(int minX, int minZ, int maxX, int maxZ) {
        return Integer.MIN_VALUE;
    }
}
