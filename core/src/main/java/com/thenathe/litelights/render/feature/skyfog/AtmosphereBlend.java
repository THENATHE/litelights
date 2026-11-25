package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.render.LiteMath;

/**
 * A weighted mix of biome atmospheres, plus the weather categories of the place. Used twice: as
 * the target the sampler builds from the biomes around the camera, and as the smoothed value the
 * renderer eases toward it, so crossing a biome border or a storm starting never pops.
 */
public final class AtmosphereBlend {
    public float hazeRed = BiomeAtmospheres.TEMPERATE.hazeRed();
    public float hazeGreen = BiomeAtmospheres.TEMPERATE.hazeGreen();
    public float hazeBlue = BiomeAtmospheres.TEMPERATE.hazeBlue();
    public float density = BiomeAtmospheres.TEMPERATE.density();
    public float mist = BiomeAtmospheres.TEMPERATE.mist();
    public float skyRed = 1.0f;
    public float skyGreen = 1.0f;
    public float skyBlue = 1.0f;
    public float sandy;
    /** The part of {@link #sandy} that is true desert: sandstorms there need only the Sandstorms switch. */
    public float desert;
    public float swampy;
    /** Where a soft wind carries dust and grit in any weather. */
    public float windy;
    /** Share of the area where precipitation falls as snow at the camera's height. */
    public float snowy;
    /** Share of the area where it can rain or snow at all (deserts and savannas can't). */
    public float wet;

    private float totalWeight;

    public void clearForSampling() {
        hazeRed = hazeGreen = hazeBlue = 0.0f;
        density = mist = 0.0f;
        skyRed = skyGreen = skyBlue = 0.0f;
        sandy = desert = swampy = windy = snowy = wet = 0.0f;
        totalWeight = 0.0f;
    }

    /**
     * @param snows        what falls here is snow
     * @param precipitates anything falls here at all
     * @param dryHeat      the season leaves the place dry and hot, so its sand can blow (always so
     *                     without a seasons mod; never in a desert the season brings rain to)
     */
    public void add(float weight, BiomeAtmosphere biome, boolean snows, boolean precipitates, boolean dryHeat) {
        hazeRed += weight * biome.hazeRed();
        hazeGreen += weight * biome.hazeGreen();
        hazeBlue += weight * biome.hazeBlue();
        density += weight * biome.density();
        mist += weight * biome.mist();
        skyRed += weight * biome.skyRed();
        skyGreen += weight * biome.skyGreen();
        skyBlue += weight * biome.skyBlue();
        if (dryHeat) {
            sandy += weight * biome.sandy();
            desert += weight * biome.desert();
        }
        swampy += weight * biome.swampy();
        windy += weight * biome.windy();
        snowy += snows ? weight : 0.0f;
        wet += precipitates ? weight : 0.0f;
        totalWeight += weight;
    }

    /** Divides the sums by the total weight. Leaves the blend untouched if nothing was added. */
    public void finishSampling() {
        if (totalWeight <= 0.0f) {
            return;
        }
        float scale = 1.0f / totalWeight;
        hazeRed *= scale;
        hazeGreen *= scale;
        hazeBlue *= scale;
        density *= scale;
        mist *= scale;
        skyRed *= scale;
        skyGreen *= scale;
        skyBlue *= scale;
        sandy *= scale;
        desert *= scale;
        swampy *= scale;
        windy *= scale;
        snowy *= scale;
        wet *= scale;
    }

    /** Moves every value a fraction of the way toward another blend. */
    public void approach(AtmosphereBlend target, float amount) {
        hazeRed = LiteMath.lerp(hazeRed, target.hazeRed, amount);
        hazeGreen = LiteMath.lerp(hazeGreen, target.hazeGreen, amount);
        hazeBlue = LiteMath.lerp(hazeBlue, target.hazeBlue, amount);
        density = LiteMath.lerp(density, target.density, amount);
        mist = LiteMath.lerp(mist, target.mist, amount);
        skyRed = LiteMath.lerp(skyRed, target.skyRed, amount);
        skyGreen = LiteMath.lerp(skyGreen, target.skyGreen, amount);
        skyBlue = LiteMath.lerp(skyBlue, target.skyBlue, amount);
        sandy = LiteMath.lerp(sandy, target.sandy, amount);
        desert = LiteMath.lerp(desert, target.desert, amount);
        swampy = LiteMath.lerp(swampy, target.swampy, amount);
        windy = LiteMath.lerp(windy, target.windy, amount);
        snowy = LiteMath.lerp(snowy, target.snowy, amount);
        wet = LiteMath.lerp(wet, target.wet, amount);
    }
}
