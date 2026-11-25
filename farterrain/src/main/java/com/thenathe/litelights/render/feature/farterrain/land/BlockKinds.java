package com.thenathe.litelights.render.feature.farterrain.land;

/**
 * What a block state is to far terrain, by palette id. The game's version reads block shapes and
 * models ({@code BlockLooks}); tests use a table.
 */
public interface BlockKinds {
    /** Not drawn and lets light and sight through: air, plants, torches, fences, panes. */
    int AIR = 0;
    /** A block drawn as a full voxel: full cubes, slabs, stairs, leaves, lava. */
    int SOLID = 1;
    /** Water: drawn where it meets air, hides what is under it. */
    int WATER = 2;

    int kind(int state);
}
