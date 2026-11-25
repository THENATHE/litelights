package com.thenathe.litelights.clouds.field;

import java.util.List;

/**
 * Everything the cloud field is made from: the input both sources fill (docs/CLOUDS-FIELD.md,
 * "The input"). Plain data, immutable, safe to hand to worker threads.
 *
 * @param seed        seeds the single clouds' shapes and lumps; the same for every player on a world
 *                    if they are to see the same sky
 * @param baseHeight  the sky's base height in world y, vanilla's cloud height in this dimension: the
 *                    floor, which no cloud's base goes below in any weather
 * @param wind        the wind at cloud height: how far it has carried the clouds by any tick
 * @param formations  the formations alive near the camera, in any order
 */
public record CloudSky(long seed, float baseHeight, CloudDrift wind, List<CloudFormation> formations) {
    public CloudSky {
        formations = List.copyOf(formations);
    }

    /** A sky without clouds. */
    public static CloudSky clear(long seed, float baseHeight) {
        return new CloudSky(seed, baseHeight, CloudWind.CALM, List.of());
    }
}
