package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettings.Storm;
import com.thenathe.litelights.render.RenderSettings.StormFog;
import com.thenathe.litelights.render.RenderSettings.Storms;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The storm fog's density with each storm type's Fog density setting (storms17). */
class StormFogTest {
    @Test
    void theDefaultsGiveTheOldDensityBitForBit() {
        Random random = new Random(17);
        StormAtmosphere storm = new StormAtmosphere();
        for (int i = 0; i < 2000; i++) {
            AtmosphereBlend land = blend(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat());
            float rain = random.nextFloat();
            float thunder = random.nextFloat();
            storm.update(land, rain, thunder, random.nextBoolean(), random.nextBoolean(), random.nextFloat(), random.nextFloat() * 0.25f);
            float before = 0.050f * (float) Math.pow(storm.sandstorm(), 1.3)
                + 0.075f * storm.blizzard()
                + 0.024f * storm.swampFog()
                + 0.005f * storm.snowFog();
            assertEquals(Float.floatToRawIntBits(before), Float.floatToRawIntBits(storm.density(Storms.DEFAULTS, thunder)));
        }
    }

    @Test
    void eachStormScalesOnlyItsOwnFog() {
        StormAtmosphere sandstorm = settled(BiomeAtmospheres.DESERT, false, 1.0f, 0.0f);
        float normal = sandstorm.density(Storms.DEFAULTS, 0.0f);
        Storms veryDenseSand = storms(StormFog.VERY_DENSE, StormFog.NORMAL);
        assertEquals(3.5f * normal, sandstorm.density(veryDenseSand, 0.0f), 1.0e-6f);
        assertEquals(normal, sandstorm.density(storms(StormFog.NORMAL, StormFog.LIGHT), 0.0f), 1.0e-7f,
            "the haboob's setting needs thunder");

        StormAtmosphere blizzard = settled(BiomeAtmospheres.SNOWY, true, 1.0f, 1.0f);
        Storms veryDenseBlizzard = new Storms(Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, new Storm(StormFog.VERY_DENSE, true),
            Storm.DEFAULT, Storm.DEFAULT);
        assertEquals(3.5f * blizzard.density(Storms.DEFAULTS, 1.0f), blizzard.density(veryDenseBlizzard, 1.0f), 1.0e-6f);
        assertEquals(blizzard.density(Storms.DEFAULTS, 1.0f), blizzard.density(veryDenseSand, 1.0f), 1.0e-7f,
            "the sandstorm's setting leaves a blizzard alone");
        Storms lightBlizzard = new Storms(Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, new Storm(StormFog.LIGHT, true),
            Storm.DEFAULT, Storm.DEFAULT);
        assertEquals(0.5f * blizzard.density(Storms.DEFAULTS, 1.0f), blizzard.density(lightBlizzard, 1.0f), 1.0e-6f);
    }

    private static Storms storms(StormFog sandstorm, StormFog haboob) {
        return new Storms(Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, new Storm(sandstorm, true), new Storm(haboob, true));
    }

    private static StormAtmosphere settled(BiomeAtmosphere biome, boolean snowy, float rain, float thunder) {
        AtmosphereBlend land = new AtmosphereBlend();
        land.clearForSampling();
        land.add(1.0f, biome, snowy, snowy, true);
        land.finishSampling();
        StormAtmosphere storm = new StormAtmosphere();
        for (int i = 0; i < 600; i++) {
            storm.update(land, rain, thunder, true, true, 1.0f, 0.05f);
        }
        return storm;
    }

    private static AtmosphereBlend blend(float sandy, float snowy, float swampy, float windy) {
        AtmosphereBlend land = new AtmosphereBlend();
        land.sandy = sandy;
        land.desert = sandy;
        land.snowy = snowy;
        land.swampy = swampy;
        land.windy = windy;
        land.wet = Math.max(snowy, swampy);
        return land;
    }
}
