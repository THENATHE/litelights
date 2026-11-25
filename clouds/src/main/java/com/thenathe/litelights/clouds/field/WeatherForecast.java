package com.thenathe.litelights.clouds.field;

/**
 * When rain and thunder will start, from the countdowns the game's own server keeps (its weather
 * data), so that where LiteLights can see them (single player) the clouds can close before the
 * rain instead of after it. Follows the server's weather cycle as it counts: each countdown flips
 * its weather when it reaches 0, and while clear weather is forced both start again the tick
 * after it ends.
 */
public final class WeatherForecast {
    /** Not coming within any countdown the server has set yet. */
    public static final int UNKNOWN = -1;

    private WeatherForecast() {
    }

    /**
     * Ticks until rain starts: 0 while it rains, {@link #UNKNOWN} when no countdown says.
     *
     * @param rainTime         the server's rain countdown (ticks until the rain flips)
     * @param clearWeatherTime ticks of forced clear weather left (the weather command's)
     */
    public static int ticksUntilRain(boolean raining, int rainTime, int clearWeatherTime) {
        if (clearWeatherTime > 0) {
            return clearWeatherTime + 1;
        }
        if (raining) {
            return 0;
        }
        return rainTime > 0 ? rainTime : UNKNOWN;
    }

    /**
     * Ticks until thunder shows, which needs the thunder and the rain at once: 0 while both are
     * on, {@link #UNKNOWN} when no countdown says or the thunder ends before the rain comes.
     */
    public static int ticksUntilThunder(boolean raining, int rainTime, boolean thundering, int thunderTime, int clearWeatherTime) {
        int untilRain = ticksUntilRain(raining, rainTime, clearWeatherTime);
        if (untilRain == UNKNOWN) {
            return UNKNOWN;
        }
        if (clearWeatherTime > 0) {
            return untilRain;
        }
        if (thundering) {
            // It thunders until its countdown ends; the rain has to come before that.
            return thunderTime > 0 && thunderTime <= untilRain ? UNKNOWN : untilRain;
        }
        if (thunderTime <= 0) {
            return UNKNOWN;
        }
        return Math.max(untilRain, thunderTime);
    }
}
