package com.thenathe.litelights.render.feature.weather;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How wetness, frost, snow and dust come and go (SurfaceWeather). */
class SurfaceWeatherTest {
    private static void run(SurfaceWeather weather, SurfaceWeather.Conditions conditions, float seconds) {
        // In frames of a sixtieth of a second, as the game moves it on.
        for (int frame = 0; frame < Math.round(seconds * 60.0f); frame++) {
            weather.update(1.0f / 60.0f, conditions);
        }
    }

    private static SurfaceWeather.Conditions raining(float rain) {
        SurfaceWeather.Conditions conditions = new SurfaceWeather.Conditions();
        conditions.rain = rain;
        return conditions;
    }

    @Test
    void glassIsWetWithinSecondsOfTheRain() {
        SurfaceWeather weather = new SurfaceWeather();
        run(weather, raining(1.0f), 1.0f);
        assertTrue(weather.wetness() > 0.3f, "already wet after a second: " + weather.wetness());
        run(weather, raining(1.0f), 4.0f);
        assertTrue(weather.wetness() > 0.9f, "soaked after five seconds: " + weather.wetness());
        assertTrue(weather.falling() > 0.95f);
    }

    @Test
    void glassDriesAboutThirtySecondsAfterTheRainStops() {
        SurfaceWeather weather = new SurfaceWeather();
        run(weather, raining(1.0f), 20.0f);
        SurfaceWeather.Conditions dry = new SurfaceWeather.Conditions();
        run(weather, dry, 15.0f);
        assertEquals(0.5f, weather.wetness(), 0.05f, "half dry after fifteen seconds");
        run(weather, dry, 15.5f);
        assertEquals(0.0f, weather.wetness(), "dry after thirty");
        assertTrue(weather.falling() < 0.01f, "running drops have stopped too");
    }

    @Test
    void lightRainOnlyWetsAsMuchAsItFalls() {
        SurfaceWeather weather = new SurfaceWeather();
        run(weather, raining(0.4f), 20.0f);
        assertEquals(0.4f, weather.wetness(), 0.01f);
        run(weather, raining(1.0f), 0.2f);
        run(weather, raining(0.4f), 1.0f);
        assertTrue(weather.wetness() > 0.4f && weather.wetness() < 0.6f, "a lighter rain lets it dry to its own level slowly");
        run(weather, raining(0.4f), 4.0f);
        assertEquals(0.4f, weather.wetness(), 0.01f);
    }

    @Test
    void nothingChangesWhilePaused() {
        SurfaceWeather weather = new SurfaceWeather();
        weather.update(0.0f, raining(1.0f));
        assertEquals(0.0f, weather.wetness());
    }

    @Test
    void frostGrowsWhereItIsColdAndClearsWhereItIsNot() {
        SurfaceWeather weather = new SurfaceWeather();
        SurfaceWeather.Conditions cold = new SurfaceWeather.Conditions();
        cold.cold = true;
        run(weather, cold, 20.0f);
        assertEquals(0.5f, weather.frost(), 0.02f);
        run(weather, cold, 25.0f);
        assertEquals(1.0f, weather.frost());
        run(weather, new SurfaceWeather.Conditions(), 15.5f);
        assertEquals(0.0f, weather.frost());
    }

    @Test
    void snowSettlesWhileItSnowsAndStaysWhileItIsCold() {
        SurfaceWeather weather = new SurfaceWeather();
        SurfaceWeather.Conditions snowing = new SurfaceWeather.Conditions();
        snowing.snow = 1.0f;
        snowing.cold = true;
        run(weather, snowing, 90.0f);
        assertEquals(1.0f, weather.snow(), 0.01f);
        SurfaceWeather.Conditions coldAndClear = new SurfaceWeather.Conditions();
        coldAndClear.cold = true;
        run(weather, coldAndClear, 60.0f);
        assertTrue(weather.snow() > 0.85f, "snow stays a long time in the cold: " + weather.snow());
        run(weather, new SurfaceWeather.Conditions(), 20.5f);
        assertEquals(0.0f, weather.snow(), "and melts where it is warm");
    }

    @Test
    void sandSettlesInAStormStaysOnSandAndRainWashesItOff() {
        SurfaceWeather weather = new SurfaceWeather();
        SurfaceWeather.Conditions storm = new SurfaceWeather.Conditions();
        storm.sandstorm = 1.0f;
        storm.sandy = true;
        storm.redSand = true;
        run(weather, storm, 25.0f);
        assertEquals(1.0f, weather.dust(), 0.01f);
        assertTrue(weather.redSand());
        SurfaceWeather.Conditions after = new SurfaceWeather.Conditions();
        after.sandy = true;
        run(weather, after, 60.0f);
        assertTrue(weather.dust() > 0.9f, "the sand stays after the storm: " + weather.dust());
        run(weather, raining(1.0f), 10.0f);
        assertEquals(0.0f, weather.dust(), "rain washes it off");
    }
}
