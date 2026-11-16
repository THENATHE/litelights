package com.thenathe.litelights.render.feature.skyfog;

/**
 * How one biome's air looks: the colour and thickness of its haze, how misty its ground gets, a
 * light tint for the Realistic sky, and which storms it can have.
 *
 * <p>Colours are daytime colours in 0..1 (sRGB); the renderer lights them for the time of day and
 * the weather. {@code density} is the haze's optical depth per block at its base height (sea level
 * in the overworld; everywhere in dimensions without a sky).
 *
 * @param sandy 0..1, how much rain here turns into a sandstorm with Storm atmosphere on (deserts,
 *              badlands, savannas)
 * @param desert 0..1, how much rain here turns into a sandstorm with only the Sandstorms switch on
 *               (true deserts; Storm atmosphere spreads storms to the rest of {@code sandy})
 * @param swampy 0..1, how much any rain here fills the air with thick fog (swamps, pale gardens)
 * @param windy 0..1, how much a soft wind carries dust and grit here in any weather (windswept
 *              hills, mountain peaks, deserts, badlands)
 * @param skyRed  sky tint for the Realistic style, 1 = no tint (also green and blue)
 */
public record BiomeAtmosphere(
    float hazeRed, float hazeGreen, float hazeBlue,
    float density,
    float mist,
    float skyRed, float skyGreen, float skyBlue,
    float sandy,
    float desert,
    float swampy,
    float windy
) {
    /** Haze density at sea level for an ordinary overworld biome (strength 100 %). */
    public static final float OVERWORLD_DENSITY = 0.0022f;

    public static BiomeAtmosphere overworld(int hazeRgb, float densityScale, float mist) {
        return new BiomeAtmosphere(red(hazeRgb), green(hazeRgb), blue(hazeRgb), OVERWORLD_DENSITY * densityScale, mist,
            1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    /** A dimension without a sky: a uniform haze of this density. */
    public static BiomeAtmosphere skyless(int hazeRgb, float density) {
        return new BiomeAtmosphere(red(hazeRgb), green(hazeRgb), blue(hazeRgb), density, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    public BiomeAtmosphere withSkyTint(float red, float green, float blue) {
        return new BiomeAtmosphere(hazeRed, hazeGreen, hazeBlue, density, mist, red, green, blue, sandy, desert, swampy, windy);
    }

    public BiomeAtmosphere withStorms(float newSandy, float newDesert, float newSwampy) {
        return new BiomeAtmosphere(hazeRed, hazeGreen, hazeBlue, density, mist, skyRed, skyGreen, skyBlue, newSandy, newDesert, newSwampy, windy);
    }

    public BiomeAtmosphere withWind(float newWindy) {
        return new BiomeAtmosphere(hazeRed, hazeGreen, hazeBlue, density, mist, skyRed, skyGreen, skyBlue, sandy, desert, swampy, newWindy);
    }

    /** The same storms and wind as another atmosphere. */
    public BiomeAtmosphere withWeatherOf(BiomeAtmosphere other) {
        return new BiomeAtmosphere(hazeRed, hazeGreen, hazeBlue, density, mist, skyRed, skyGreen, skyBlue,
            other.sandy, other.desert, other.swampy, other.windy);
    }

    public BiomeAtmosphere withHaze(int hazeRgb) {
        return new BiomeAtmosphere(red(hazeRgb), green(hazeRgb), blue(hazeRgb), density, mist, skyRed, skyGreen, skyBlue,
            sandy, desert, swampy, windy);
    }

    public BiomeAtmosphere withDensity(float newDensity) {
        return new BiomeAtmosphere(hazeRed, hazeGreen, hazeBlue, newDensity, mist, skyRed, skyGreen, skyBlue, sandy, desert, swampy, windy);
    }

    static float red(int rgb) {
        return ((rgb >> 16) & 0xFF) / 255.0f;
    }

    static float green(int rgb) {
        return ((rgb >> 8) & 0xFF) / 255.0f;
    }

    static float blue(int rgb) {
        return (rgb & 0xFF) / 255.0f;
    }
}
