package com.thenathe.litelights.render;

import com.thenathe.litelights.render.RenderSettings.Storm;
import com.thenathe.litelights.render.RenderSettings.StormFog;
import com.thenathe.litelights.render.RenderSettings.Storms;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each storm type's look (storms17). The defaults must leave every fog and particle value exactly
 * as it was, bit for bit, at any thunder level, and a storm partway into thunder blends its two
 * types' settings.
 */
class StormLooksTest {
    private static final float[] THUNDER = {0.0f, 1.0e-7f, 0.1f, 0.3f, 1.0f / 3.0f, 0.5f, 0.77f, 0.999f, 1.0f};

    @Test
    void theDefaultsAreExactlyOneAtEveryThunderLevel() {
        Storms storms = Storms.DEFAULTS;
        for (float thunder : THUNDER) {
            assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(storms.rainFog(thunder)));
            assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(storms.snowFog(thunder)));
            assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(storms.sandFog(thunder)));
            assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(storms.rainClouds(thunder)));
            assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(storms.snowClouds(thunder)));
            assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(storms.sandClouds(thunder)));
        }
        assertEquals(StormFog.NORMAL, RenderSettings.Weather.DEFAULTS.storms().blizzard().fog());
        assertEquals(1.0f, StormFog.NORMAL.thickness);
    }

    @Test
    void eachLevelIsThickerThanTheLast() {
        float last = 0.0f;
        for (StormFog fog : StormFog.values()) {
            assertEquals(true, fog.thickness > last, fog + " is thicker than the level before");
            last = fog.thickness;
        }
        assertEquals(3.5f, StormFog.VERY_DENSE.thickness);
    }

    @Test
    void thunderBlendsTheCalmAndTheThunderyType() {
        Storm veryDense = new Storm(StormFog.VERY_DENSE, true);
        Storm noClouds = new Storm(StormFog.NORMAL, false);
        Storms haboobOnly = new Storms(Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, veryDense);
        assertEquals(1.0f, haboobOnly.sandFog(0.0f), "a sandstorm without thunder is a sandstorm");
        assertEquals(3.5f, haboobOnly.sandFog(1.0f), "with thunder it is a haboob");
        assertEquals(2.25f, haboobOnly.sandFog(0.5f), 1.0e-6f);
        assertEquals(1.0f, haboobOnly.rainFog(1.0f), "the other storms keep their own");

        Storms rainWithoutMist = new Storms(noClouds, Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT);
        assertEquals(0.0f, rainWithoutMist.rainClouds(0.0f));
        assertEquals(1.0f, rainWithoutMist.rainClouds(1.0f), "a thunderstorm keeps its mist");
        assertEquals(0.25f, rainWithoutMist.rainClouds(0.25f), 1.0e-6f);
        assertEquals(1.0f, rainWithoutMist.snowClouds(0.0f), "snow keeps its mist");
    }
}
