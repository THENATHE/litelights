package com.thenathe.litelights.render.frame;

import com.thenathe.litelights.config.PreviewWeather;
import com.thenathe.litelights.render.LiteMath;

/**
 * The settings screen's weather preview as the renderer sees it: the rain and thunder levels and
 * the kind of land everything should be drawn for, blended with the world's own weather so a
 * preview closes in and clears over about a second instead of popping.
 *
 * <p>Every LiteLights effect that follows the weather reads the levels through here rather than
 * from the level: the frame's weather (fog, sky, sun and shadows, the sunbeams' thunder fade), the
 * particle spawners and the storm model. Nothing here touches the world; vanilla's own rain, sounds
 * and weather logic keep running on the real weather.
 *
 * <p>Updated once per frame on the render thread ({@link #update}); also read by the client tick,
 * which runs on the same thread.
 */
public final class WeatherPreview {
    /** How quickly the preview blends in and out, and moves between two previewed weathers, per second. */
    private static final float BLEND_RATE = 4.0f;
    /** After a preview ends, storms and sunbeams keep easing quickly this long, so the preview's storm clears at once too. */
    private static final long SETTLE_NANOS = 4_000_000_000L;

    private static PreviewWeather.Fall fall;
    private static float mix;
    private static float rain;
    private static float thunder;
    private static long lastNanos;
    private static long lastShownNanos;

    private WeatherPreview() {
    }

    /** Eases toward the screen's current choice. Call once per frame before anything reads the weather. */
    public static void update() {
        long now = System.nanoTime();
        float seconds = lastNanos == 0L ? 0.0f : Math.min((now - lastNanos) / 1.0e9f, 0.25f);
        lastNanos = now;
        if (mix > 0.0f) {
            lastShownNanos = now;
        }
        PreviewWeather.Weather wanted = PreviewWeather.current();
        float amount = seconds <= 0.0f ? 0.0f : 1.0f - (float) Math.exp(-BLEND_RATE * seconds);
        if (wanted == null) {
            mix = mix < 0.002f ? 0.0f : LiteMath.lerp(mix, 0.0f, amount);
            if (mix == 0.0f) {
                fall = null;
            }
            return;
        }
        if (mix == 0.0f) {
            // Starting from no preview: the blend carries the change, the levels start where they end.
            rain = wanted.rainLevel();
            thunder = wanted.thunderLevel();
        } else {
            rain = LiteMath.lerp(rain, wanted.rainLevel(), amount);
            thunder = LiteMath.lerp(thunder, wanted.thunderLevel(), amount);
        }
        fall = wanted.fall();
        mix = mix > 0.998f ? 1.0f : LiteMath.lerp(mix, 1.0f, Math.max(amount, 0.001f));
    }

    /** Whether the screen is previewing a weather right now (not counting the fade after it ends). */
    public static boolean previewing() {
        return PreviewWeather.current() != null;
    }

    /** Whether the preview shows at all this frame, blending in or out included. */
    public static boolean active() {
        return mix > 0.0f;
    }

    /**
     * Whether what follows the weather should ease quickly: while the preview shows, and for a few
     * seconds after it ends, so a previewed storm clears as fast as it came instead of over the
     * real weather's half a minute.
     */
    public static boolean settling() {
        return mix > 0.0f || lastShownNanos != 0L && System.nanoTime() - lastShownNanos < SETTLE_NANOS;
    }

    /** How much of the preview shows, 0 to 1. */
    public static float mix() {
        return mix;
    }

    /** What falls in the previewed weather, or null when nothing is previewed. */
    public static PreviewWeather.Fall fall() {
        return fall;
    }

    /** The rain level to draw with, given the world's. */
    public static float rain(float worldRain) {
        return LiteMath.lerp(worldRain, rain, mix);
    }

    /** The thunder level to draw with, given the world's. */
    public static float thunder(float worldThunder) {
        return LiteMath.lerp(worldThunder, thunder, mix);
    }

    /** The share of the land where it snows: all of it in a snow preview, none in rain or sand. */
    public static float snowyLand(float worldShare) {
        return fall == null ? worldShare : LiteMath.lerp(worldShare, fall == PreviewWeather.Fall.SNOW ? 1.0f : 0.0f, mix);
    }

    /** The share of the land where a sandstorm may rise: all of it in a sand preview, none otherwise. */
    public static float sandyLand(float worldShare) {
        return fall == null ? worldShare : LiteMath.lerp(worldShare, fall == PreviewWeather.Fall.SAND ? 1.0f : 0.0f, mix);
    }

    /** The share of the land where rain and snow can fall: all of it in a rain or snow preview, none in sand. */
    public static float wetLand(float worldShare) {
        return fall == null ? worldShare : LiteMath.lerp(worldShare, fall == PreviewWeather.Fall.SAND ? 0.0f : 1.0f, mix);
    }
}
