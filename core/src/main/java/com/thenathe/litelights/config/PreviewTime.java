package com.thenathe.litelights.config;

import java.util.OptionalLong;

/**
 * The settings screen's time-of-day preview. While a preview is on, LiteLights draws its sky,
 * light and colour grade as if it were that time of day; the world's real time never changes.
 * The renderer reads {@link #overrideDayTime()} each frame; the screen sets and clears it, and
 * clears it again when it closes.
 */
public final class PreviewTime {
    /** The times the screen offers, with the time of day (in ticks, 0 to 23999) each one stands for. */
    public enum Time {
        DAWN(23300),
        NOON(6000),
        DUSK(12700),
        NIGHT(18000);

        private final long dayTime;

        Time(long dayTime) {
            this.dayTime = dayTime;
        }

        public long dayTime() {
            return dayTime;
        }
    }

    private static volatile Time current;

    private PreviewTime() {
    }

    /** The previewed time, or null when there is no preview. */
    public static Time current() {
        return current;
    }

    /** The day time (0 to 23999) LiteLights should draw with instead of the world's, if a preview is on. */
    public static OptionalLong overrideDayTime() {
        Time time = current;
        return time == null ? OptionalLong.empty() : OptionalLong.of(time.dayTime());
    }

    public static boolean active() {
        return current != null;
    }

    public static void set(Time time) {
        current = time;
    }

    public static void clear() {
        current = null;
    }
}
