package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.frame.WeatherPreview;

/**
 * The storms at the camera, the one model both the fog and the weather particles follow (storm
 * ideas by TheDeathlyCow, Immersive Storms; built here only from the descriptions in
 * docs/PLAN.md):
 * <ul>
 *   <li>a sandstorm closes in over deserts while it rains (the Sandstorms switch), and with Storm
 *   atmosphere also over badlands and savannas, thicker in a thunderstorm;</li>
 *   <li>snowy weather whitens the fog, and thunder where it snows becomes a blizzard;</li>
 *   <li>swamps and pale gardens fill with thick fog in any rain;</li>
 *   <li>windswept hills, mountain peaks, deserts and badlands have a soft wind carrying dust and
 *   grit in any weather.</li>
 * </ul>
 * Each intensity is 0..1, eased in time (storms close in over several seconds) and already blended
 * across biome borders by the atmosphere sampler.
 *
 * <p>Read {@link #current()} on the render or client thread; shaders get the storm fog they make
 * (LiteStormFog), not these values. They are
 * 0 when their switches are off, when Immersive Storms is loaded, underground and in dimensions
 * whose weather switch is off, so sand, wind dust and dark rain can follow them directly.
 */
public final class StormAtmosphere {
    /** Storms close in and clear over a few seconds. */
    private static final float EASE_RATE = 0.45f;
    /** A previewed storm from the settings screen closes in within about half a second. */
    private static final float PREVIEW_EASE_RATE = 5.0f;

    private static final StormAtmosphere CURRENT = new StormAtmosphere();

    private float sandstorm;
    private float blizzard;
    private float swampFog;
    private float snowFog;
    private float windDust;
    private float sandThunder;
    private boolean atmosphere;

    StormAtmosphere() {
    }

    /** The live storm values, updated once per frame before the level draws. */
    public static StormAtmosphere current() {
        return CURRENT;
    }

    /** Sandstorm strength: 0.6 in rain, 1 in a thunderstorm, over the sandy land that may have one. */
    public float sandstorm() {
        return sandstorm;
    }

    /**
     * The thunder a sandstorm blows with, 0 to 1: the world's, which with Almanac isn't the player's
     * share. Decides how much of a sandstorm is a haboob.
     */
    public float sandThunder() {
        return sandThunder;
    }

    /** Blizzard strength: thunder where it snows. */
    public float blizzard() {
        return blizzard;
    }

    /** Thick fog in swamps and pale gardens during rain. */
    public float swampFog() {
        return swampFog;
    }

    /** Any snowfall: the fog turns whiter. */
    public float snowFog() {
        return snowFog;
    }

    /** The soft wind's dust and grit, 0..1, in any weather: Storm atmosphere's ambient wind. */
    public float windDust() {
        return windDust;
    }

    /**
     * Whether Storm atmosphere applies here: switched on, not handled by another mod, and allowed
     * in this dimension. Decides where its sandstorms reach and whether pale-garden rain falls dark.
     */
    public boolean atmosphere() {
        return atmosphere;
    }

    /**
     * Eases toward the storm the weather and the land call for.
     *
     * @param atmosphere Storm atmosphere is on and allowed here (not handled by another mod)
     * @param sandstorms the Sandstorms switch is on and allowed here; on its own it raises storms
     *                   only over deserts, with Storm atmosphere over every sandy biome
     * @param exposure how open to the sky the camera is (storms don't reach into caves)
     */
    void update(AtmosphereBlend land, float rain, float thunder, boolean atmosphere, boolean sandstorms, float exposure, float seconds) {
        update(land, rain, thunder, rain, thunder, atmosphere, sandstorms, exposure, seconds);
    }

    /**
     * The same, with the world's rain and thunder for the sandstorm. A seasons mod that shares the
     * rain out by climate (Almanac) leaves the player in a desert none while it rains elsewhere, yet
     * the desert's sand is what that rain becomes there.
     */
    void update(AtmosphereBlend land, float rain, float thunder, float sandRain, float sandThunder, boolean atmosphere,
                boolean sandstorms, float exposure, float seconds) {
        this.sandThunder = sandThunder;
        float sandTarget = 0.0f;
        float blizzardTarget = 0.0f;
        float swampTarget = 0.0f;
        float snowTarget = 0.0f;
        float windTarget = 0.0f;
        this.atmosphere = atmosphere;
        float open = LiteMath.smoothstep(0.2f, 0.8f, exposure);
        // A weather previewed from the settings screen falls on any land: snow everywhere for
        // snow, sand everywhere for a sandstorm (WeatherPreview passes the real land through otherwise).
        float snowy = WeatherPreview.snowyLand(land.snowy);
        if (sandstorms) {
            // Deserts and savannas have no rain of their own: the storm is the rain there.
            float sandyLand = WeatherPreview.sandyLand(atmosphere ? land.sandy : land.desert);
            sandTarget = sandyLand * sandRain * (0.6f + 0.4f * sandThunder) * open;
        }
        if (atmosphere) {
            snowTarget = snowy * rain * open;
            blizzardTarget = snowy * thunder * open;
            swampTarget = land.swampy * rain * open;
            windTarget = land.windy * open;
        }
        float amount = SkyFogMath.approachFactor(WeatherPreview.settling() ? PREVIEW_EASE_RATE : EASE_RATE, seconds);
        sandstorm = LiteMath.lerp(sandstorm, sandTarget, amount);
        blizzard = LiteMath.lerp(blizzard, blizzardTarget, amount);
        swampFog = LiteMath.lerp(swampFog, swampTarget, amount);
        snowFog = LiteMath.lerp(snowFog, snowTarget, amount);
        windDust = LiteMath.lerp(windDust, windTarget, amount);
    }

    void clear() {
        sandstorm = blizzard = swampFog = snowFog = windDust = sandThunder = 0.0f;
        atmosphere = false;
    }

    /**
     * Storm fog density per block from the intensities: a full sandstorm hides land past about 60
     * blocks, a blizzard past about 40, swamp fog past about 110, snowfall alone only whitens.
     * Each storm type's Fog density setting scales its part (swamp fog is rain's): at Normal each
     * factor is exactly 1, so the default is the fog as it always was.
     */
    float density(RenderSettings.Storms storms, float thunder) {
        return 0.050f * (float) Math.pow(sandstorm, 1.3) * storms.sandFog(sandThunder)
            + 0.075f * blizzard * storms.blizzard().fog().thickness
            + 0.024f * swampFog * storms.rainFog(thunder)
            + 0.005f * snowFog * storms.snowFog(thunder);
    }
}
