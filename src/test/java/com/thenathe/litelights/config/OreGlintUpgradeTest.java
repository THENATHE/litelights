package com.thenathe.litelights.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * shimmer099 split ores and crystals out of Glint on sand and stone and added Heat shimmer. A file
 * from 0.9.8 keeps its sand-and-stone value as it was, whichever it is, and the new rows take their
 * defaults (both on).
 */
class OreGlintUpgradeTest {
    @TempDir
    static Path configFolder;

    @TempDir
    Path folder;

    @BeforeAll
    static void loadOptions() {
        if (!LiteLightsConfig.loaded()) {
            LiteLightsConfig.useFile(configFolder.resolve("litelights.json"));
        }
        LiteLightsConfig.init();
    }

    @Test
    void sandAndStoneSwitchedOnStaysOnAndTheNewRowsTakeTheirDefaults() throws IOException {
        load("true");
        try {
            assertTrue(LiteLightsOptions.GLINT_SAND_STONE.on());
            assertTrue(LiteLightsOptions.GLINT_ORES.on());
            assertTrue(LiteLightsOptions.HEAT_SHIMMER.on());
        } finally {
            LiteLightsConfig.registry().resetAll();
        }
    }

    @Test
    void sandAndStoneLeftOffStaysOffAndIsSavedAsItWas() throws IOException {
        Path path = load("false");
        try {
            assertFalse(LiteLightsOptions.GLINT_SAND_STONE.on());
            assertTrue(LiteLightsOptions.GLINT_ORES.on());
            new ConfigFile(path, LiteLightsConfig.registry(), ConfigMigrations.standard()).save();
            JsonObject options = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("options");
            assertFalse(options.get("lighting.glint_sand_stone").getAsBoolean());
            assertTrue(options.get("lighting.glint_ores").getAsBoolean());
            assertTrue(options.get("lighting.heat_shimmer").getAsBoolean());
        } finally {
            LiteLightsConfig.registry().resetAll();
        }
    }

    private Path load(String sandAndStone) throws IOException {
        Path path = folder.resolve("litelights.json");
        Files.writeString(path, """
                {
                  "schema": 3,
                  "options": { "lighting.glint_sand_stone": %s, "lighting.glint_ice_snow": true }
                }""".formatted(sandAndStone));
        new ConfigFile(path, LiteLightsConfig.registry(), ConfigMigrations.standard()).load();
        return path;
    }
}
