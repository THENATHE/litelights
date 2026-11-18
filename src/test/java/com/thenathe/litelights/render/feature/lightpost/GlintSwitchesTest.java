package com.thenathe.litelights.render.feature.lightpost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.thenathe.litelights.render.RenderSettings;
import org.junit.jupiter.api.Test;

/** The glint switches' bits as light_post/surface_light.glsl and ore_glint.glsl read them. */
class GlintSwitchesTest {
    private static final RenderSettings.Lighting DEFAULTS = RenderSettings.Lighting.DEFAULTS;

    @Test
    void gentleOreSparklesLeaveBitSevenClear() {
        int switches = (int) LightPostFrame.glintSwitches(DEFAULTS);
        assertEquals(64, switches & 64);
        assertEquals(0, switches & 128);
    }

    @Test
    void stillOreSparklesSetBitSevenAndNothingElse() {
        int gentle = (int) LightPostFrame.glintSwitches(DEFAULTS);
        int still = (int) LightPostFrame.glintSwitches(withOreSparkle(RenderSettings.OreSparkle.STILL));
        assertEquals(gentle | 128, still);
    }

    private static RenderSettings.Lighting withOreSparkle(RenderSettings.OreSparkle sparkle) {
        RenderSettings.Lighting d = DEFAULTS;
        return new RenderSettings.Lighting(d.halo(), d.haloStrength(), d.glowHeat(), d.sunbeams(), d.sunbeamStrength(),
            d.underwaterSunbeams(), d.underwaterSunbeamStrength(), d.sunbeamQuality(), d.glintWater(), d.waterSparkle(),
            d.glintIceSnow(), d.glintMetalGlass(), d.glintSandStone(), d.submergedGlint(), d.glintWaterSoftness(),
            d.glintWaterIntensity(), d.glintGroundSoftness(), d.glintGroundIntensity(), d.nightColorTone(), d.glintOres(),
            d.heatShimmer(), sparkle);
    }
}
