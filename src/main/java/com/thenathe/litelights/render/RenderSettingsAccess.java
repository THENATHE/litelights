package com.thenathe.litelights.render;

import java.util.Objects;

/**
 * The single place render code reads settings from. The config system publishes a fresh snapshot
 * whenever a value changes; until it does, the PLAN defaults apply.
 *
 * <p>Snapshots are immutable, so a frame that reads {@link #current()} once sees one consistent set
 * of values even if the settings screen publishes a new snapshot mid-frame.
 */
public final class RenderSettingsAccess {
    private static volatile RenderSettings current = RenderSettings.DEFAULTS;

    private RenderSettingsAccess() {
    }

    public static RenderSettings current() {
        return current;
    }

    public static void publish(RenderSettings settings) {
        current = Objects.requireNonNull(settings, "settings");
    }
}
