package com.thenathe.litelights.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Settings saved by 1.0.7 still load into 1.0.8, which dropped Voxy and Distant Horizons support
 * and with it the rows that only acted on their terrain. A file with every one of those rows away
 * from its default loads without a fuss, every other value comes through, and the removed values
 * stay in the file, as RemovedOptionsTest asks of earlier removals.
 */
class FarTerrainDropTest {
    /** The rows 1.0.8 removed, as 1.0.7 saved them, each away from its default. */
    private static final List<String> REMOVED = List.of("fog.far_terrain", "fog.far_snow", "water.far_terrain",
        "water.far_caustics", "water.far_depth_colour", "water.far_edge_foam");

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
    void aFileFrom107WithTheFarRowsChangedLoadsAndKeepsEverythingElse() throws IOException {
        Path path = folder.resolve("litelights.json");
        Files.writeString(path, """
                {
                  "schema": 3,
                  "options": {
                    "fog.far_terrain": false,
                    "fog.far_snow": false,
                    "water.far_terrain": false,
                    "water.far_caustics": true,
                    "water.far_depth_colour": true,
                    "water.far_edge_foam": true,
                    "shadows.far": "off",
                    "shadows.reach": 192,
                    "fog.mist_banks": false,
                    "water.edge_foam": false
                  }
                }""");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            ConfigFile file = new ConfigFile(path, registry, ConfigMigrations.standard());
            file.load();
            for (String removed : REMOVED) {
                assertTrue(registry.all().stream().noneMatch(option -> option.id().equals(removed)), removed + " is no longer an option");
            }
            assertEquals(LiteLightsOptions.FarShadows.OFF, LiteLightsOptions.FAR_SHADOWS.get());
            assertEquals(192, LiteLightsOptions.SHADOW_REACH.value());
            assertFalse(LiteLightsOptions.MIST_BANKS.on());
            assertFalse(LiteLightsOptions.EDGE_FOAM.on());
            assertTrue(LiteLightsOptions.OCEAN_WAVES.on(), "an option the file doesn't mention stays at its default");

            file.save();
            JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonObject options = saved.getAsJsonObject("options");
            assertFalse(options.get("fog.far_terrain").getAsBoolean(), "the removed rows' values stay in the file");
            assertTrue(options.get("water.far_edge_foam").getAsBoolean());
            assertEquals("off", options.get("shadows.far").getAsString());
            assertEquals(192, options.get("shadows.reach").getAsInt());
            assertFalse(options.get("water.edge_foam").getAsBoolean());
            assertFalse(saved.has("unreadable") && saved.getAsJsonObject("unreadable").size() > 0, "nothing was unreadable");
        } finally {
            registry.resetAll();
        }
    }
}
