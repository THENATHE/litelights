package com.thenathe.litelights.render.feature.water.splash;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether Streams Reflowing throws its own spray where falling water lands. It sprays at every
 * waterfall in view, about twenty times as many particles as LiteLights' mist, foam and droplets,
 * so while it does, LiteLights' waterfall particles step aside (as the jump splash does for Wakes)
 * and only its shader effects stay: the white falling water and the foam where it lands.
 *
 * <p>Read from its public client settings file only ({@code streamBubbles}: 0 is off;
 * {@code particleDensity}: 0 throws none), again whenever the file changes, at most every few
 * seconds. A file that is missing or can't be read counts as spraying, its default. Without the
 * mod installed nothing is read and LiteLights' particles stay.
 */
public final class StreamsReflowingSpray {
    private static final String MOD_ID = "streamsreflowing";
    private static final String SETTINGS_FILE = "streamsreflowing-client.properties";
    private static final int TICKS_BETWEEN_CHECKS = 100;

    private final boolean installed;
    private final Path settings;
    private boolean sprays = true;
    private FileTime readAt;
    private int ticksToCheck;

    public StreamsReflowingSpray() {
        FabricLoader loader = FabricLoader.getInstance();
        this.installed = loader.isModLoaded(MOD_ID);
        this.settings = loader.getConfigDir().resolve(SETTINGS_FILE);
    }

    public boolean installed() {
        return installed;
    }

    /** Once per client tick: whether Streams Reflowing draws the spray at waterfalls right now. */
    public boolean spraysWaterfalls() {
        if (!installed) {
            return false;
        }
        if (--ticksToCheck <= 0) {
            ticksToCheck = TICKS_BETWEEN_CHECKS;
            refresh();
        }
        return sprays;
    }

    private void refresh() {
        try {
            FileTime modified = Files.getLastModifiedTime(settings);
            if (modified.equals(readAt)) {
                return;
            }
            readAt = modified;
            Properties values = new Properties();
            try (Reader reader = Files.newBufferedReader(settings)) {
                values.load(reader);
            }
            sprays = spraysWith(values);
        } catch (IOException | RuntimeException unreadable) {
            readAt = null;
            sprays = true;
        }
    }

    /** The settings that decide it, with Streams Reflowing's own defaults for any that are missing. */
    static boolean spraysWith(Properties values) {
        int bubbles = parseInt(values.getProperty("streamBubbles"), 2);
        double density = parseDouble(values.getProperty("particleDensity"), 1.0);
        return bubbles != 0 && density > 0.0;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }
}
