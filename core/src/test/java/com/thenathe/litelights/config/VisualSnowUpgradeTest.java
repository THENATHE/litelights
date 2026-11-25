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
 * Settings saved by 1.0.13 load unchanged into the snow round's build: every value they hold comes
 * through, the visual snow's new rows take their defaults (the master off, so the picture is as
 * before), and saving keeps what was there.
 */
class VisualSnowUpgradeTest {
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
    void aFileFrom1013LoadsUnchangedAndTheNewRowsTakeTheirDefaults() throws IOException {
        Path path = folder.resolve("litelights.json");
        Files.writeString(path, """
                {
                  "schema": 3,
                  "options": {
                    "weather.snowy_leaves": false,
                    "weather.density": "dense",
                    "weather.puddles": false,
                    "particles.density": "sparse"
                  }
                }""");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            ConfigFile file = new ConfigFile(path, registry, ConfigMigrations.standard());
            file.load();
            assertFalse(LiteLightsOptions.SNOWY_LEAVES.on());
            assertEquals(LiteLightsOptions.WeatherDensity.DENSE, LiteLightsOptions.WEATHER_DENSITY.get());
            assertFalse(LiteLightsOptions.PUDDLES.on());
            assertFalse(LiteLightsOptions.VISUAL_SNOW.on(), "the master switch starts off: the picture and cost as before");
            assertTrue(LiteLightsOptions.SNOW_ON_BLOCKS.on(), "each row starts on, for when the master is turned on");
            assertTrue(LiteLightsOptions.FAR_SNOW.on());
            assertEquals(LiteLightsOptions.SnowyLeavesLook.WHOLE, LiteLightsOptions.SNOWY_LEAVES_LOOK.get(), "today's near look");
            assertFalse(LiteLightsOptions.SNOW_ON_BLOCKS.active(), "and is greyed out while the master is off");

            file.save();
            JsonObject options = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("options");
            assertFalse(options.get("weather.snowy_leaves").getAsBoolean());
            assertEquals("dense", options.get("weather.density").getAsString());
            assertEquals("sparse", options.get("particles.density").getAsString());
        } finally {
            registry.resetAll();
        }
    }
}
