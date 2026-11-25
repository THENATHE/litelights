package com.thenathe.litelights.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the four glint sliders stand for (followb097), and how their values reach the shader. */
class GlintScaleTest {
    private static final float CLOSE = 1.0e-6f;

    @Test
    void theDefaultsAreTheAgreedLooks() {
        assertEquals(0.80f, GlintScale.waterSoftness(0.5f), CLOSE, "water at 50% is the old softness 0.80");
        assertEquals(1.0f, GlintScale.waterIntensity(0.5f), CLOSE, "water at 50% is as bright as before");
        assertEquals(0.0f, GlintScale.groundSoftness(0.5f), CLOSE, "ground at 50% is the old softness 0");
        assertEquals(0.5f, GlintScale.groundIntensity(0.5f), CLOSE, "ground at 50% is half as bright as before");
    }

    @Test
    void eachSliderHasRoomBothWays() {
        assertEquals(0.0f, GlintScale.waterSoftness(0.0f), CLOSE, "the sharpest water is the old 0");
        assertEquals(GlintScale.WATER_SOFTEST, GlintScale.waterSoftness(1.0f), CLOSE);
        assertTrue(GlintScale.WATER_SOFTEST > 1.0f, "the softest water is softer than the old slider went");
        assertEquals(GlintScale.GROUND_CRISPEST, GlintScale.groundSoftness(0.0f), CLOSE);
        assertTrue(GlintScale.GROUND_CRISPEST < 0.0f, "the crispest ground is crisper than the old 0");
        assertEquals(1.0f, GlintScale.groundSoftness(1.0f), CLOSE, "the softest ground is the old 1");
        assertEquals(0.0f, GlintScale.waterIntensity(0.0f), CLOSE);
        assertEquals(2.0f, GlintScale.waterIntensity(1.0f), CLOSE);
        assertEquals(0.0f, GlintScale.groundIntensity(0.0f), CLOSE);
        assertEquals(1.0f, GlintScale.groundIntensity(1.0f), CLOSE, "the brightest ground is today's brightness");
    }

    @Test
    void theScalesRiseSteadily() {
        for (int step = 1; step <= 100; step++) {
            float lower = (step - 1) / 100.0f;
            float upper = step / 100.0f;
            assertTrue(GlintScale.waterSoftness(upper) > GlintScale.waterSoftness(lower));
            assertTrue(GlintScale.groundSoftness(upper) > GlintScale.groundSoftness(lower));
            assertTrue(GlintScale.waterIntensity(upper) > GlintScale.waterIntensity(lower));
            assertTrue(GlintScale.groundIntensity(upper) > GlintScale.groundIntensity(lower));
        }
        assertEquals(GlintScale.waterSoftness(1.0f), GlintScale.waterSoftness(7.0f), CLOSE, "out-of-range values are held at the ends");
        assertEquals(GlintScale.groundSoftness(0.0f), GlintScale.groundSoftness(-3.0f), CLOSE);
    }

    @Test
    void anOldSoftnessFindsTheSliderThatLooksTheSame() {
        for (int hundredths = 0; hundredths <= 100; hundredths += 5) {
            float old = hundredths / 100.0f;
            float slider = GlintScale.waterSliderForOldSoftness(old);
            assertEquals(old, GlintScale.waterSoftness(slider), 1.0e-5f, "old softness " + old);
        }
        assertEquals(0.5f, GlintScale.waterSliderForOldSoftness(0.80f), CLOSE);
        assertEquals(0.0f, GlintScale.waterSliderForOldSoftness(0.0f), CLOSE);
    }

    @Test
    void packingIsExactAtTheDefaultsAndEnds() {
        assertEquals(0.80f, unpack(GlintScale.pack(0.80f, 1.0f))[0], 1.0e-5f);
        assertEquals(1.0f, unpack(GlintScale.pack(0.80f, 1.0f))[1], 1.0e-5f);
        assertEquals(0.0f, unpack(GlintScale.pack(0.0f, 0.5f))[0], 1.0e-5f);
        assertEquals(0.5f, unpack(GlintScale.pack(0.0f, 0.5f))[1], 1.0e-5f);
        assertEquals(-1.0f, unpack(GlintScale.pack(-1.0f, 0.0f))[0], 1.0e-5f);
        assertEquals(1.5f, unpack(GlintScale.pack(1.5f, 2.0f))[0], 1.0e-5f);
        assertEquals(2.0f, unpack(GlintScale.pack(1.5f, 2.0f))[1], 1.0e-5f);
        for (int slider = 0; slider <= 100; slider++) {
            float position = slider / 100.0f;
            float[] water = unpack(GlintScale.pack(GlintScale.waterSoftness(position), GlintScale.waterIntensity(position)));
            assertEquals(GlintScale.waterSoftness(position), water[0], 0.0051f);
            assertEquals(GlintScale.waterIntensity(position), water[1], 0.0051f);
        }
    }

    @Test
    void theGroundTuningRidesAboveTheSwitchesExactlyAsAFloat() {
        int switches = 63;
        int ground = GlintScale.pack(GlintScale.groundSoftness(1.0f), GlintScale.groundIntensity(1.0f));
        int packed = switches | ground << 8;
        float sent = packed;
        assertEquals(packed, (int) (sent + 0.5f), "below 2^24, so the float is exact");
        int highest = 63 | GlintScale.pack(1.55f, 2.55f) << 8;
        assertTrue(highest < 1 << 24);
        assertEquals(switches, (int) sent & 255);
        assertEquals(1.0f, unpack((int) sent >> 8)[0], 1.0e-5f);
    }

    /** As lite_lp_glint_tuning in light_post/surface_light.glsl. */
    private static float[] unpack(int packed) {
        return new float[] {(packed & 255) * 0.01f - 1.0f, (packed >> 8 & 255) * 0.01f};
    }
}
