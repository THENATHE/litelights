package com.thenathe.litelights.overlays.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.overlays.OverlaysModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Keeps the overlays module's settings in LiteLights' config, and imports the standalone Dynamic
 * Overlays mod's settings the first time LiteLights runs.
 *
 * <p>The settings live in {@code config/litelights.json} under {@code modules.overlays}, written by
 * whatever {@link ModuleData} is given (the LiteLights config in the game, a plain object in tests).
 *
 * <p>The import only ever reads {@code config/dynamic_overlays.json}: the file is never moved,
 * changed or deleted, and nothing is written beside it, so the standalone mod keeps working if the
 * player goes back to it. It runs only while LiteLights holds no overlay settings, so once the
 * player has LiteLights settings they are never overwritten by the old ones.
 */
public final class OverlayConfigStore {
    /** The module's key under {@code modules} in litelights.json. */
    public static final String MODULE_ID = "overlays";
    /** The standalone mod's settings, relative to the game's config folder. */
    public static final String DYNAMIC_OVERLAYS_FILE = "dynamic_overlays.json";

    /** Where the module's settings are kept. */
    public interface ModuleData {
        /** Whether anything is saved for the module yet. */
        boolean exists();

        /** The saved object; only called when {@link #exists()}. */
        JsonObject read();

        /** Replaces what is saved and writes it out. */
        void write(JsonObject data);

        /** Keeps an unreadable saved object somewhere it won't be overwritten. */
        void keepUnreadable(JsonObject data);
    }

    /** Where the settings came from on this load. */
    public enum Origin {
        /** LiteLights' own saved settings. */
        LITELIGHTS,
        /** Imported from Dynamic Overlays' file, and saved as LiteLights' own. */
        IMPORTED,
        /** Nothing was saved anywhere, or nothing could be read. */
        DEFAULTS
    }

    public record Loaded(OverlayConfig config, Origin origin) {
    }

    private OverlayConfigStore() {
    }

    public static Loaded load(ModuleData data, Path configDir) {
        if (data.exists()) {
            try {
                OverlayConfig read = OverlayConfig.GSON.fromJson(data.read(), OverlayConfig.class);
                if (read != null) {
                    return new Loaded(read.upgraded(), Origin.LITELIGHTS);
                }
            } catch (RuntimeException failure) {
                // The next save would replace it, so a copy goes aside first.
                data.keepUnreadable(data.read().deepCopy());
                OverlaysModule.LOG.warn("The saved overlay settings couldn't be read; kept a copy and using defaults", failure);
            }
            return new Loaded(new OverlayConfig(), Origin.DEFAULTS);
        }
        Path standalone = configDir.resolve(DYNAMIC_OVERLAYS_FILE);
        if (Files.isRegularFile(standalone)) {
            OverlayConfig imported = readStandalone(standalone);
            if (imported != null) {
                save(imported, data);
                OverlaysModule.LOG.info("Imported the overlay settings from {}; that file is left as it was", standalone);
                return new Loaded(imported, Origin.IMPORTED);
            }
            OverlaysModule.LOG.warn("Couldn't import {}; the overlays start from their defaults", standalone);
        }
        return new Loaded(new OverlayConfig(), Origin.DEFAULTS);
    }

    /** The upgraded settings from Dynamic Overlays' file, or {@code null} if it can't be read. */
    private static OverlayConfig readStandalone(Path file) {
        try {
            JsonElement tree = JsonParser.parseString(Files.readString(file));
            OverlayConfig read = OverlayConfig.GSON.fromJson(tree, OverlayConfig.class);
            return read == null ? null : read.upgraded();
        } catch (IOException | RuntimeException failure) {
            OverlaysModule.LOG.warn("{} couldn't be read", file, failure);
            return null;
        }
    }

    /**
     * Writes the settings. Keys already saved that the settings don't know (left by another version
     * of the module, including features that were later removed) are kept as they were, the same way
     * the shared config keeps option ids nobody registered.
     */
    public static void save(OverlayConfig config, ModuleData data) {
        JsonObject written = OverlayConfig.GSON.toJsonTree(config).getAsJsonObject();
        if (data.exists()) {
            for (Map.Entry<String, JsonElement> saved : data.read().entrySet()) {
                if (!written.has(saved.getKey())) {
                    written.add(saved.getKey(), saved.getValue().deepCopy());
                }
            }
        }
        data.write(written);
    }

    /** Copies {@code source}'s entries into {@code target}, replacing what was there. */
    public static void replaceContents(JsonObject target, JsonObject source) {
        for (String key : target.keySet().toArray(String[]::new)) {
            target.remove(key);
        }
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            target.add(entry.getKey(), entry.getValue());
        }
    }
}
