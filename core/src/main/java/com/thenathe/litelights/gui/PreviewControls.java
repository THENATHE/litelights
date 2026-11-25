package com.thenathe.litelights.gui;

import com.thenathe.litelights.config.BoolOption;
import com.thenathe.litelights.config.LiteLightsOptions;
import com.thenathe.litelights.config.PreviewEvent;
import com.thenathe.litelights.config.PreviewTime;
import com.thenathe.litelights.config.PreviewWeather;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The three preview buttons at the bottom of the settings screen: time of day, weather and a sky
 * event. Each steps through its choices, Off first, one click at a time. They only change what
 * LiteLights draws; the world's time and weather never move, and everything ends when the screen
 * closes ({@link #clearAll}).
 *
 * <p>Sky events but the rainbow are night events: choosing one previews the night too (the
 * rainbow, noon unless another daytime is previewed), and moving the time to one the event can't
 * show at turns the event off again, so the two never disagree. A previewed event shows where the
 * player is looking.
 */
enum PreviewControls {
    TIME("time") {
        @Override
        int count() {
            return PreviewTime.Time.values().length + 1;
        }

        @Override
        int current() {
            PreviewTime.Time time = PreviewTime.current();
            return time == null ? 0 : time.ordinal() + 1;
        }

        @Override
        void select(int index) {
            PreviewTime.Time time = index == 0 ? null : PreviewTime.Time.values()[index - 1];
            if (time == null) {
                PreviewTime.clear();
            } else {
                PreviewTime.set(time);
            }
            PreviewEvent.Event event = PreviewEvent.current();
            if (event != null && !event.suits(time)) {
                PreviewEvent.clear();
            }
        }

        @Override
        Glyphs glyph(int index) {
            return switch (index) {
                case 1 -> Glyphs.SUNRISE;
                case 2 -> Glyphs.SUN;
                case 3 -> Glyphs.SUNSET;
                case 4 -> Glyphs.MOON;
                default -> Glyphs.CLOCK;
            };
        }

        @Override
        String choiceKey(int index) {
            return "litelights.preview." + PreviewTime.Time.values()[index - 1].name().toLowerCase(Locale.ROOT);
        }
    },

    WEATHER("weather") {
        @Override
        int count() {
            return PreviewWeather.Weather.values().length + 1;
        }

        @Override
        int current() {
            PreviewWeather.Weather weather = PreviewWeather.current();
            return weather == null ? 0 : weather.ordinal() + 1;
        }

        @Override
        void select(int index) {
            if (index == 0) {
                PreviewWeather.clear();
            } else {
                PreviewWeather.set(PreviewWeather.Weather.values()[index - 1]);
            }
        }

        @Override
        Glyphs glyph(int index) {
            return switch (index) {
                case 1 -> Glyphs.RAIN;
                case 2 -> Glyphs.STORM;
                case 3 -> Glyphs.SNOW;
                case 4 -> Glyphs.BLIZZARD;
                case 5 -> Glyphs.SANDSTORM;
                case 6 -> Glyphs.SAND_THUNDER;
                default -> Glyphs.CLOUD;
            };
        }

        @Override
        String choiceKey(int index) {
            return "litelights.preview.weather." + PreviewWeather.Weather.values()[index - 1].name().toLowerCase(Locale.ROOT);
        }

        @Override
        BoolOption switchFor(int index) {
            PreviewWeather.Weather weather = index == 0 ? null : PreviewWeather.Weather.values()[index - 1];
            return weather != null && weather.fall() == PreviewWeather.Fall.SAND ? LiteLightsOptions.SANDSTORMS : null;
        }

        @Override
        String asideKey(int index) {
            return index > 0 && PreviewWeather.Weather.values()[index - 1] == PreviewWeather.Weather.THUNDER_SANDSTORM
                    ? choiceKey(index) + ".tooltip" : null;
        }
    },

    EVENT("event") {
        @Override
        int count() {
            return PreviewEvent.Event.values().length + 1;
        }

        @Override
        int current() {
            PreviewEvent.Event event = PreviewEvent.current();
            return event == null ? 0 : event.ordinal() + 1;
        }

        @Override
        void select(int index) {
            if (index == 0) {
                PreviewEvent.clear();
                return;
            }
            PreviewEvent.Event event = PreviewEvent.Event.values()[index - 1];
            if (!event.suits(PreviewTime.current())) {
                PreviewTime.set(event.atNight() ? PreviewTime.Time.NIGHT : PreviewTime.Time.NOON);
            }
            aimEvent(event);
        }

        @Override
        Glyphs glyph(int index) {
            return switch (index) {
                case 1 -> Glyphs.AURORA;
                case 2 -> Glyphs.METEOR;
                case 3 -> Glyphs.FIREWORKS;
                case 4 -> Glyphs.FALLING_STAR;
                case 5 -> Glyphs.RAINBOW;
                default -> Glyphs.STAR;
            };
        }

        @Override
        String choiceKey(int index) {
            return "litelights.preview.event." + PreviewEvent.Event.values()[index - 1].name().toLowerCase(Locale.ROOT);
        }

        @Override
        BoolOption switchFor(int index) {
            return switch (index) {
                case 1 -> LiteLightsOptions.POLAR_LIGHTS;
                case 2 -> LiteLightsOptions.METEOR_NIGHTS;
                case 3 -> LiteLightsOptions.FAR_FIREWORKS;
                case 4 -> LiteLightsOptions.FALLING_STARS;
                case 5 -> LiteLightsOptions.RAINBOWS;
                default -> null;
            };
        }
    };

    private final String key;

    PreviewControls(String key) {
        this.key = key;
    }

    /** How many choices the button steps through, Off included. */
    abstract int count();

    /** The choice showing now; 0 is Off. */
    abstract int current();

    abstract void select(int index);

    abstract Glyphs glyph(int index);

    /** The translation key of a choice other than Off. */
    abstract String choiceKey(int index);

    /** The switch a choice needs to show anything, if it has one. */
    BoolOption switchFor(int index) {
        return null;
    }

    /** A line of hover text about the choice itself, if it has one (Haboob's), as a translation key. */
    String asideKey(int index) {
        return null;
    }

    /** Whether the switch a choice needs is off, so the preview would show nothing of it. */
    boolean switchedOff(int index) {
        BoolOption needed = switchFor(index);
        return needed != null && !needed.on();
    }

    void advance() {
        select((current() + 1) % count());
    }

    /** The button's text for a choice: the choice's name, or for Off the kind of preview. */
    Component name(int index) {
        return Component.translatable(index == 0 ? "litelights.preview." + key + ".off" : choiceKey(index));
    }

    /**
     * What the button does: the preview showing now and what the next click shows, a reminder
     * that the world doesn't change, and a note when the setting it needs is switched off.
     */
    Component tooltip() {
        int index = current();
        int next = (index + 1) % count();
        Component now = index == 0 ? Component.translatable("litelights.preview.off") : name(index);
        Component then = next == 0 ? Component.translatable("litelights.preview.off") : name(next);
        var text = Component.translatable("litelights.preview." + key + ".tooltip", now, then);
        String aside = asideKey(index);
        if (aside != null) {
            text.append("\n\n").append(Component.translatable(aside).withStyle(ChatFormatting.ITALIC));
        }
        if (switchedOff(index)) {
            BoolOption needed = switchFor(index);
            text.append("\n\n").append(Component.translatable("litelights.preview.switched_off",
                    Component.translatable("litelights.option." + needed.id())));
        }
        return text;
    }

    /**
     * Shows a sky event where the player is looking: at the middle of the live world beside the
     * docked panel (or of the window, full screen), at the height the camera looks at. The
     * screen can't turn the camera, so the event comes to the view instead.
     */
    private static void aimEvent(PreviewEvent.Event event) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (minecraft.level == null || camera == null) {
            PreviewEvent.set(event);
            return;
        }
        float across = minecraft.gui.screen() instanceof SettingsScreen screen ? screen.liveWorldCentre() : 0.5f;
        double aspect = minecraft.getWindow().getWidth() / (double) Math.max(1, minecraft.getWindow().getHeight());
        double halfWidth = Math.tan(Math.toRadians(camera.getFov()) * 0.5) * aspect;
        double sideways = Math.atan((across * 2.0 - 1.0) * halfWidth);
        // Yaw 180 faces north; bearings count from north, turning east.
        double bearing = Math.toRadians(camera.yRot()) - Math.PI + sideways;
        PreviewEvent.set(event, (float) bearing, (float) Math.toRadians(-camera.xRot()));
    }

    /** Ends every preview; the screen calls this when it goes away. */
    static void clearAll() {
        PreviewTime.clear();
        PreviewWeather.clear();
        PreviewEvent.clear();
    }
}
