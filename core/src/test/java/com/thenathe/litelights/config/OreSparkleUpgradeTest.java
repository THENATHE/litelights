package com.thenathe.litelights.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * oreglint added Ore sparkle motion under Glint on ores and crystals. A file from 0.9.9 keeps every
 * glint row as it was saved, ores off included, and the new row takes its default, Gentle.
 */
class OreSparkleUpgradeTest {
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
    void oresLeftOnGetGentleSparkles() throws IOException {
        load("true");
        try {
            assertTrue(LiteLightsOptions.GLINT_ORES.on());
            assertTrue(LiteLightsOptions.GLINT_SAND_STONE.on());
            assertEquals(LiteLightsOptions.WaterSparkle.LIVELY, LiteLightsOptions.WATER_SPARKLE.get());
            assertEquals(LiteLightsOptions.OreSparkle.GENTLE, LiteLightsOptions.ORE_SPARKLE.get());
        } finally {
            LiteLightsConfig.registry().resetAll();
        }
    }

    @Test
    void oresSwitchedOffStayOffAndAreSavedAsTheyWere() throws IOException {
        Path path = load("false");
        try {
            assertFalse(LiteLightsOptions.GLINT_ORES.on());
            assertEquals(LiteLightsOptions.OreSparkle.GENTLE, LiteLightsOptions.ORE_SPARKLE.get());
            new ConfigFile(path, LiteLightsConfig.registry(), ConfigMigrations.standard()).save();
            JsonObject options = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("options");
            assertFalse(options.get("lighting.glint_ores").getAsBoolean());
            assertTrue(options.get("lighting.glint_sand_stone").getAsBoolean());
            assertEquals("lively", options.get("lighting.water_sparkle").getAsString());
            assertEquals("gentle", options.get("lighting.ore_sparkle").getAsString());
        } finally {
            LiteLightsConfig.registry().resetAll();
        }
    }

    private Path load(String ores) throws IOException {
        Path path = folder.resolve("litelights.json");
        Files.writeString(path, """
                {
                  "schema": 3,
                  "options": {
                    "lighting.glint_ores": %s,
                    "lighting.glint_sand_stone": true,
                    "lighting.water_sparkle": "lively",
                    "lighting.heat_shimmer": true
                  }
                }""".formatted(ores));
        new ConfigFile(path, LiteLightsConfig.registry(), ConfigMigrations.standard()).load();
        return path;
    }
}
