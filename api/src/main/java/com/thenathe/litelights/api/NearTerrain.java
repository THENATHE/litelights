package com.thenathe.litelights.api;

/**
 * Where the game's own terrain (vanilla's or Sodium's) is, for a renderer that draws beyond it.
 * Render thread only.
 */
public interface NearTerrain {
    /** The render distance in effect, in chunks: the player's, or the server's when that is lower. */
    int renderDistanceChunks();

    /**
     * Whether the near renderer has a mesh for this section and has faded it in far enough to be
     * seen. Vanilla's own check, which Sodium answers for its renderer, so no Sodium code is named.
     */
    boolean sectionDrawn(int sectionX, int sectionY, int sectionZ);

    /** Sodium draws the near terrain. */
    boolean sodium();
}
