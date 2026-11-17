package com.thenathe.litelights.config;

/**
 * The settings screen's sky event preview: an aurora, a meteor shower, distant fireworks or
 * falling stars shown tonight, or a rainbow by day, wherever the player is (the aurora too, not
 * only in cold biomes). The screen previews the night along with the night events and the day
 * with the rainbow. Only LiteLights' own sky draws change; the world and its schedule of events
 * are left alone.
 *
 * <p>A previewed event is placed where the player was looking when it was chosen (the live world
 * beside the docked settings panel), so it shows straight away whichever way they face.
 */
public final class PreviewEvent {
    /** The events the screen offers, in the order its button steps through them. */
    public enum Event {
        AURORA, METEOR_SHOWER, FIREWORKS, FALLING_STARS, RAINBOW;

        /** Whether the event belongs to the night (all but the rainbow, which shows by day). */
        public boolean atNight() {
            return this != RAINBOW;
        }

        /** Whether the event can show at a previewed time of day (null: no time preview). */
        public boolean suits(PreviewTime.Time time) {
            return time != null && (time == PreviewTime.Time.NIGHT) == atNight();
        }
    }

    private static volatile Event current;
    private static volatile float bearing;
    private static volatile float elevation;

    private PreviewEvent() {
    }

    /** The previewed event, or null when there is no preview. */
    public static Event current() {
        return current;
    }

    public static boolean active() {
        return current != null;
    }

    /**
     * Previews an event toward a direction: {@code bearing} in radians from north (toward -z)
     * turning east (toward +x), {@code elevation} in radians above the horizon.
     */
    public static void set(Event event, float bearing, float elevation) {
        PreviewEvent.bearing = bearing;
        PreviewEvent.elevation = elevation;
        current = event;
    }

    /** Previews an event toward the north, low over the horizon: where a real night's aurora hangs. */
    public static void set(Event event) {
        set(event, 0.0f, 0.0f);
    }

    /** Where the previewed event is placed: radians from north, turning east. */
    public static float bearing() {
        return bearing;
    }

    /** The height above the horizon the preview was aimed at, in radians. */
    public static float elevation() {
        return elevation;
    }

    public static void clear() {
        current = null;
    }
}
