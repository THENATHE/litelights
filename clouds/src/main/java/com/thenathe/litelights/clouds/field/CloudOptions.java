package com.thenathe.litelights.clouds.field;

/**
 * The choices the user has not made yet, as parameters, so changing one needs no rewrite
 * (docs/CLOUDS-FIELD.md, "Numbers to decide").
 *
 * @param nearCubeBlocks   the cube size near the camera: 8 (the design), 10, or 4 to 6; columns
 *                         are asked for at this width there, and clouds smaller than a cube are left out
 * @param reachBlocks      how far the clouds reach: about 8 km (8,192) or about 16 km (16,384)
 * @param followServerSeed whether the sky on a server follows the world's hashed seed, which vanilla
 *                         sends every player, so all LiteLights players there see the same sky
 */
public record CloudOptions(int nearCubeBlocks, int reachBlocks, boolean followServerSeed) {
    public static final CloudOptions DEFAULT = new CloudOptions(8, 8_192, true);

    public CloudOptions {
        if (nearCubeBlocks < 1 || reachBlocks < nearCubeBlocks) {
            throw new IllegalArgumentException("nearCubeBlocks must be 1 or more and reachBlocks at least that");
        }
    }

    /**
     * The seed the sky is made from.
     *
     * @param hashedWorldSeed the hashed seed vanilla sends with the level (its biome zoom seed); one-way,
     *                        so it gives nothing of the world seed away
     * @param ownSeed         a seed of this player's own, for when the sky isn't shared
     */
    public long skySeed(long hashedWorldSeed, long ownSeed) {
        return followServerSeed ? hashedWorldSeed : ownSeed;
    }
}
