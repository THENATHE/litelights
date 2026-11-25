package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** When the server's countdowns say rain and thunder will start. */
class WeatherForecastTest {
    @Test
    void rain() {
        assertEquals(0, WeatherForecast.ticksUntilRain(true, 5_000, 0), "raining now");
        assertEquals(700, WeatherForecast.ticksUntilRain(false, 700, 0));
        assertEquals(WeatherForecast.UNKNOWN, WeatherForecast.ticksUntilRain(false, 0, 0), "a new countdown is about to be drawn");
        assertEquals(1_201, WeatherForecast.ticksUntilRain(false, 50, 1_200), "forced clear weather: rain the tick after it ends");
    }

    @Test
    void thunderNeedsTheRainToo() {
        assertEquals(0, WeatherForecast.ticksUntilThunder(true, 4_000, true, 3_000, 0));
        assertEquals(900, WeatherForecast.ticksUntilThunder(false, 900, true, 5_000, 0), "thundering already: shows with the rain");
        assertEquals(WeatherForecast.UNKNOWN, WeatherForecast.ticksUntilThunder(false, 900, true, 400, 0), "the thunder ends before the rain");
        assertEquals(1_500, WeatherForecast.ticksUntilThunder(true, 9_000, false, 1_500, 0));
        assertEquals(2_000, WeatherForecast.ticksUntilThunder(false, 2_000, false, 600, 0), "the later of the two");
        assertEquals(WeatherForecast.UNKNOWN, WeatherForecast.ticksUntilThunder(false, 0, false, 600, 0));
    }
}
