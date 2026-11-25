package com.thenathe.litelights.config;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The shared config: one registry of options and the file it is saved to,
 * {@code config/litelights.json}. The file is read the first time anything asks for the registry,
 * so it doesn't matter which entrypoint touches an option first.
 */
public final class LiteLightsConfig {
    public static final String FILE_NAME = "litelights.json";

    private static OptionRegistry registry;
    private static ConfigFile file;
    private static Path pathOverride;

    private LiteLightsConfig() {
    }

    public static synchronized OptionRegistry registry() {
        if (registry == null) {
            registry = new OptionRegistry();
            Path path = pathOverride != null ? pathOverride : FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
            file = new ConfigFile(path, registry, ConfigMigrations.standard());
            file.load();
        }
        return registry;
    }

    /** For tests: reads and writes this file instead of the game's. Must be called before anything else. */
    static synchronized void useFile(Path path) {
        if (registry != null) {
            throw new IllegalStateException("The config is already loaded");
        }
        pathOverride = path;
    }

    /** For tests that share one JVM: whether the config has been loaded already. */
    static synchronized boolean loaded() {
        return registry != null;
    }

    /** Makes sure every built-in option exists and the file has been read. */
    public static void init() {
        registry();
        LiteLightsOptions.init();
    }

    public static synchronized ConfigFile file() {
        registry();
        return file;
    }

    public static Path path() {
        return file().path();
    }

    /** Writes the file now. */
    public static void save() {
        file().save();
    }

    /** Writes the file if anything changed since the last save. */
    public static void saveIfChanged() {
        if (registry().isDirty()) {
            save();
        }
    }

    /** A module's own data, kept in litelights.json under "modules". Call {@link #save()} after changing it. */
    public static JsonObject module(String id) {
        return file().module(id);
    }

    public static boolean hasModule(String id) {
        return file().hasModule(id);
    }
}
