package com.thenathe.litelights.render.feature.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettings.VisualSnow;
import org.junit.jupiter.api.Test;

/** More snow in snowstorms: more flakes with the row on, the same as before with it off. */
class MoreSnowTest {
    private static RenderSettings.Weather with(VisualSnow visual) {
        RenderSettings.Weather d = RenderSettings.Weather.DEFAULTS;
        return new RenderSettings.Weather(d.precipitation(), d.density(), d.rainMist(), d.driftingSand(), d.sandstorms(),
            d.stormAtmosphere(), d.polarLights(), d.meteorNights(), d.farFireworks(), d.distance(), d.distanceFill(), d.rainDrips(),
            d.wallStreaks(), d.glass(), d.storms(), d.mistStyle(), d.puddles(), d.rainbows(), d.snowyLeaves(), visual);
    }

    @Test
    void offAsBefore() {
        assertEquals(1.0f, WeatherParticles.moreSnow(RenderSettings.Weather.DEFAULTS, 0.0f));
        assertEquals(1.0f, WeatherParticles.moreSnow(RenderSettings.Weather.DEFAULTS, 1.0f));
        VisualSnow masterOff = VisualSnow.of(false, true, true, true, true, RenderSettings.SnowyLeavesLook.WHOLE, true, true);
        assertEquals(1.0f, WeatherParticles.moreSnow(with(masterOff), 1.0f));
    }

    @Test
    void onMoreInCalmSnowAndMostInABlizzard() {
        VisualSnow on = VisualSnow.of(true, true, true, true, true, RenderSettings.SnowyLeavesLook.WHOLE, true, true);
        assertEquals(WeatherParticles.MORE_CALM_SNOW, WeatherParticles.moreSnow(with(on), 0.0f));
        assertEquals(WeatherParticles.MORE_BLIZZARD_SNOW, WeatherParticles.moreSnow(with(on), 1.0f));
        assertEquals(1.35f, WeatherParticles.moreSnow(with(on), 0.5f), 1e-6);
    }
}
