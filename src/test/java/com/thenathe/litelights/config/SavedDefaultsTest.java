package com.thenathe.litelights.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.config.LiteLightsOptions.StormFog;
import com.thenathe.litelights.config.LiteLightsOptions.StormType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defaults changed in defaults098 reach new installs only. A settings file from 0.9.7 holds
 * every option, the old defaults included, and must load and save exactly as it was; a file that
 * lacks a key gets the new default for it.
 */
class SavedDefaultsTest {
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
    void aFileFrom097KeepsTheOldDefaults() throws IOException {
        Path path = folder.resolve("litelights.json");
        Files.writeString(path, """
                {
                  "schema": 3,
                  "options": {
                    "sky.palette": "vanilla_colours",
                    "sky.cloud_layers": 1,
                    "vegetation.sway_grass": false,
                    "vegetation.sway_plants": false,
                    "vegetation.sway_leaves": false,
                    "water.palette": "vanilla_colours",
                    "water.foam_motion": "stationary",
                    "water.ocean_waves": false,
                    "outlines.hide_shimmer": false,
                    "shadows.reach": 256,
                    "weather.storms.rain.fog": "normal",
                    "weather.storms.snow.fog": "normal",
                    "weather.storms.sandstorm.fog": "normal"
                  }
                }""");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            ConfigFile file = new ConfigFile(path, registry, ConfigMigrations.standard());
            file.load();
            assertEquals(LiteLightsOptions.SkyPalette.VANILLA_COLOURS, LiteLightsOptions.SKY_PALETTE.get());
            assertEquals(1, LiteLightsOptions.CLOUD_LAYERS.value());
            assertFalse(LiteLightsOptions.SWAY_GRASS.on());
            assertFalse(LiteLightsOptions.SWAY_PLANTS.on());
            assertFalse(LiteLightsOptions.SWAY_LEAVES.on());
            assertEquals(LiteLightsOptions.WaterPalette.VANILLA_COLOURS, LiteLightsOptions.WATER_PALETTE.get());
            assertEquals(LiteLightsOptions.FoamMotion.STATIONARY, LiteLightsOptions.FOAM_MOTION.get());
            assertFalse(LiteLightsOptions.OCEAN_WAVES.on());
            assertFalse(LiteLightsOptions.OUTLINE_HIDE_SHIMMER.on());
            assertEquals(256, LiteLightsOptions.SHADOW_REACH.value());
            assertEquals(StormFog.NORMAL, LiteLightsOptions.storm(StormType.RAIN).fog().get());
            assertEquals(StormFog.NORMAL, LiteLightsOptions.storm(StormType.SANDSTORM).fog().get());

            file.save();
            JsonObject options = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("options");
            assertEquals("vanilla_colours", options.get("sky.palette").getAsString());
            assertEquals(256, options.get("shadows.reach").getAsInt());
            assertFalse(options.get("water.ocean_waves").getAsBoolean());
            assertEquals("normal", options.get("weather.storms.snow.fog").getAsString());
            assertFalse(Files.exists(folder.resolve("litelights.json.schema3.bak")), "a current file needs no migration");
        } finally {
            registry.resetAll();
        }
    }

    @Test
    void aMissingKeyGetsTheNewDefault() throws IOException {
        Path path = folder.resolve("litelights.json");
        Files.writeString(path, """
                {
                  "schema": 3,
                  "options": { "shadows.reach": 320 }
                }""");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            new ConfigFile(path, registry, ConfigMigrations.standard()).load();
            assertEquals(320, LiteLightsOptions.SHADOW_REACH.value());
            assertEquals(LiteLightsOptions.SkyPalette.COMPUTED, LiteLightsOptions.SKY_PALETTE.get());
            assertTrue(LiteLightsOptions.OCEAN_WAVES.on());
            assertEquals(StormFog.LIGHT, LiteLightsOptions.storm(StormType.SNOW).fog().get());
            assertEquals(StormFog.NORMAL, LiteLightsOptions.storm(StormType.BLIZZARD).fog().get());
        } finally {
            registry.resetAll();
        }
    }
}
