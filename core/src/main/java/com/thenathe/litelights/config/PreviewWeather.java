package com.thenathe.litelights.config;

/**
 * The settings screen's weather preview. While one is on, LiteLights draws its fog, storms, sky,
 * sunbeams and its own rain, snow and sand as if that weather were falling here, whatever the
 * biome. The world's weather never changes: no real rain starts and vanilla plays no sounds for
 * it. The screen sets and clears it, and clears it again when it closes.
 */
public final class PreviewWeather {
    /** What falls from the sky in a previewed weather. */
    public enum Fall { RAIN, SNOW, SAND }

    /** The weathers the screen offers, in the order its button steps through them. */
    public enum Weather {
        RAIN(Fall.RAIN, false),
        THUNDERSTORM(Fall.RAIN, true),
        SNOW(Fall.SNOW, false),
        BLIZZARD(Fall.SNOW, true),
        SANDSTORM(Fall.SAND, false),
        THUNDER_SANDSTORM(Fall.SAND, true);

        private final Fall fall;
        private final boolean thunder;

        Weather(Fall fall, boolean thunder) {
            this.fall = fall;
            this.thunder = thunder;
        }

        public Fall fall() {
            return fall;
        }

        /** The rain level drawn with: every previewed weather is a full rain (sandstorms need it too). */
        public float rainLevel() {
            return 1.0f;
        }

        public float thunderLevel() {
            return thunder ? 1.0f : 0.0f;
        }
    }

    private static volatile Weather current;

    private PreviewWeather() {
    }

    /** The previewed weather, or null when there is no preview. */
    public static Weather current() {
        return current;
    }

    public static boolean active() {
        return current != null;
    }

    public static void set(Weather weather) {
        current = weather;
    }

    public static void clear() {
        current = null;
    }
}
