package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.render.RenderSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtmosphereBlendTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void weightedMixAcrossABorder() {
        AtmosphereBlend blend = new AtmosphereBlend();
        blend.clearForSampling();
        blend.add(3.0f, BiomeAtmospheres.DESERT, false, false, true);
        blend.add(1.0f, BiomeAtmospheres.SWAMP, false, true, true);
        blend.finishSampling();
        assertEquals(0.75f * BiomeAtmospheres.DESERT.hazeRed() + 0.25f * BiomeAtmospheres.SWAMP.hazeRed(), blend.hazeRed, EPSILON);
        assertEquals(0.75f, blend.sandy, EPSILON);
        assertEquals(0.25f, blend.swampy, EPSILON);
        assertEquals(0.25f, blend.wet, EPSILON);
        assertEquals(0.0f, blend.snowy, EPSILON);
    }

    @Test
    void emptySamplingKeepsTheLastValues() {
        AtmosphereBlend blend = new AtmosphereBlend();
        float before = blend.hazeBlue;
        blend.finishSampling();
        assertEquals(before, blend.hazeBlue, EPSILON);
    }

    @Test
    void approachMovesPartWay() {
        AtmosphereBlend from = new AtmosphereBlend();
        AtmosphereBlend to = new AtmosphereBlend();
        to.clearForSampling();
        to.add(1.0f, BiomeAtmospheres.PALE_GARDEN, true, true, true);
        to.finishSampling();
        float start = from.density;
        from.approach(to, 0.25f);
        assertEquals(start + 0.25f * (to.density - start), from.density, EPSILON);
        assertEquals(0.25f, from.snowy, EPSILON);
        from.approach(to, 1.0f);
        assertEquals(to.density, from.density, EPSILON);
    }

    @Test
    void aDesertTheSeasonBringsRainToHasNoSandstorm() {
        AtmosphereBlend winterDesert = new AtmosphereBlend();
        winterDesert.clearForSampling();
        winterDesert.add(1.0f, BiomeAtmospheres.DESERT, false, true, false);
        winterDesert.finishSampling();
        assertEquals(0.0f, winterDesert.sandy, EPSILON);
        assertEquals(0.0f, winterDesert.desert, EPSILON);
        assertEquals(1.0f, winterDesert.wet, EPSILON, "it rains there instead");
        StormAtmosphere storm = new StormAtmosphere();
        for (int i = 0; i < 600; i++) {
            storm.update(winterDesert, 1.0f, 1.0f, true, true, 1.0f, 0.05f);
        }
        assertEquals(0.0f, storm.sandstorm(), EPSILON);
    }

    @Test
    void stormsFollowWeatherAndLand() {
        AtmosphereBlend desert = new AtmosphereBlend();
        desert.clearForSampling();
        desert.add(1.0f, BiomeAtmospheres.DESERT, false, false, true);
        desert.finishSampling();
        StormAtmosphere storm = new StormAtmosphere();
        for (int i = 0; i < 600; i++) {
            storm.update(desert, 1.0f, 1.0f, true, true, 1.0f, 0.05f);
        }
        assertEquals(1.0f, storm.sandstorm(), 0.01f);
        assertEquals(0.0f, storm.blizzard(), EPSILON);
        assertTrue(storm.density(RenderSettings.Storms.DEFAULTS, 1.0f) > 0.04f);

        for (int i = 0; i < 600; i++) {
            storm.update(desert, 1.0f, 0.0f, true, true, 1.0f, 0.05f);
        }
        assertEquals(0.6f, storm.sandstorm(), 0.01f);

        // Underground, switched off, or with the Sandstorms switch off: the sandstorm clears.
        for (int i = 0; i < 600; i++) {
            storm.update(desert, 1.0f, 1.0f, true, true, 0.0f, 0.05f);
        }
        assertEquals(0.0f, storm.sandstorm(), 0.01f);
        for (int i = 0; i < 600; i++) {
            storm.update(desert, 1.0f, 1.0f, true, false, 1.0f, 0.05f);
        }
        assertEquals(0.0f, storm.sandstorm(), 0.01f);
    }

    @Test
    void blizzardNeedsSnowAndThunder() {
        AtmosphereBlend snowy = new AtmosphereBlend();
        snowy.clearForSampling();
        snowy.add(1.0f, BiomeAtmospheres.SNOWY, true, true, true);
        snowy.finishSampling();
        StormAtmosphere storm = new StormAtmosphere();
        for (int i = 0; i < 600; i++) {
            storm.update(snowy, 1.0f, 0.0f, true, true, 1.0f, 0.05f);
        }
        assertEquals(0.0f, storm.blizzard(), 0.01f);
        assertEquals(1.0f, storm.snowFog(), 0.01f);
        for (int i = 0; i < 600; i++) {
            storm.update(snowy, 1.0f, 1.0f, true, true, 1.0f, 0.05f);
        }
        assertEquals(1.0f, storm.blizzard(), 0.01f);
    }

    @Test
    void sandstormsWithoutStormAtmosphereStayInDeserts() {
        StormAtmosphere desertStorm = settled(blendOf(BiomeAtmospheres.DESERT), 1.0f, 1.0f, false, true);
        assertEquals(1.0f, desertStorm.sandstorm(), 0.01f);
        assertEquals(0.0f, desertStorm.windDust(), 0.01f);
        assertFalse(desertStorm.atmosphere());

        StormAtmosphere badlands = settled(blendOf(BiomeAtmospheres.BADLANDS), 1.0f, 1.0f, false, true);
        assertEquals(0.0f, badlands.sandstorm(), 0.01f);
        StormAtmosphere badlandsWithAtmosphere = settled(blendOf(BiomeAtmospheres.BADLANDS), 1.0f, 1.0f, true, true);
        assertEquals(1.0f, badlandsWithAtmosphere.sandstorm(), 0.01f);
        StormAtmosphere savanna = settled(blendOf(BiomeAtmospheres.SAVANNA), 1.0f, 1.0f, true, true);
        assertEquals(0.8f, savanna.sandstorm(), 0.01f);
    }

    @Test
    void windDustBlowsInClearWeatherWhereItIsWindy() {
        StormAtmosphere hills = settled(blendOf(BiomeAtmospheres.WINDSWEPT), 0.0f, 0.0f, true, true);
        assertEquals(1.0f, hills.windDust(), 0.01f);
        assertEquals(0.0f, hills.sandstorm(), EPSILON);
        assertEquals(0.0f, hills.blizzard() + hills.swampFog() + hills.snowFog(), EPSILON, "wind dust is not storm fog");

        assertEquals(0.0f, settled(blendOf(BiomeAtmospheres.WINDSWEPT), 0.0f, 0.0f, false, true).windDust(), 0.01f);
        assertEquals(0.0f, settled(blendOf(BiomeAtmospheres.SWAMP), 0.0f, 0.0f, true, true).windDust(), 0.01f);
        assertEquals(1.0f, settled(blendOf(BiomeAtmospheres.DESERT), 0.0f, 0.0f, true, true).windDust(), 0.01f);
    }

    private static AtmosphereBlend blendOf(BiomeAtmosphere biome) {
        AtmosphereBlend blend = new AtmosphereBlend();
        blend.clearForSampling();
        blend.add(1.0f, biome, false, true, true);
        blend.finishSampling();
        return blend;
    }

    private static StormAtmosphere settled(AtmosphereBlend land, float rain, float thunder, boolean atmosphere, boolean sandstorms) {
        StormAtmosphere storm = new StormAtmosphere();
        for (int i = 0; i < 600; i++) {
            storm.update(land, rain, thunder, atmosphere, sandstorms, 1.0f, 0.05f);
        }
        return storm;
    }

    @Test
    void stormsEaseInOverSeconds() {
        AtmosphereBlend swamp = new AtmosphereBlend();
        swamp.clearForSampling();
        swamp.add(1.0f, BiomeAtmospheres.SWAMP, false, true, true);
        swamp.finishSampling();
        StormAtmosphere storm = new StormAtmosphere();
        storm.update(swamp, 1.0f, 0.0f, true, true, 1.0f, 1.0f / 60.0f);
        assertTrue(storm.swampFog() < 0.02f, "a storm must not pop in: " + storm.swampFog());
    }
}
