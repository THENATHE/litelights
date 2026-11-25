package com.thenathe.litelights.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * Settings saved before an option was removed still load into the built-in options: Breath on glass
 * (weather.glass_breath) went in 0.1.6 and Rain on glass (weather.glass_style) in 0.1.7, and a file
 * that has them must load without a fuss, keep every other value, and keep the removed ones in the
 * file rather than drop them.
 */
class RemovedOptionsTest {
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
    void aFileWithRemovedGlassOptionsLoadsAndKeepsTheirValues() throws IOException {
        Path path = folder.resolve("litelights.json");
        // As 0.1.6's pre-release builds wrote it, with the player's own glass settings: 0.1.6 itself
        // wrote the same without weather.glass_breath.
        Files.writeString(path, """
                {
                  "schema": 2,
                  "options": {
                    "weather.glass": true,
                    "weather.glass_style": "streaks",
                    "weather.glass_detail": "texels_32",
                    "weather.glass_frost": false,
                    "weather.glass_dust": true,
                    "weather.glass_breath": false,
                    "water.edge_foam": false
                  }
                }""");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            ConfigFile file = new ConfigFile(path, registry, ConfigMigrations.standard());
            file.load();
            assertTrue(registry.all().stream().noneMatch(option -> option.id().equals("weather.glass_breath")),
                "Breath on glass is no longer an option");
            assertTrue(registry.all().stream().noneMatch(option -> option.id().equals("weather.glass_style")),
                "Rain on glass is no longer an option");
            assertTrue(LiteLightsOptions.GLASS_WEATHER.on());
            assertEquals(LiteLightsOptions.GlassDetail.TEXELS_32, LiteLightsOptions.GLASS_DETAIL.get());
            assertFalse(LiteLightsOptions.GLASS_FROST.on());
            assertFalse(LiteLightsOptions.EDGE_FOAM.on());
            assertEquals(LiteLightsOptions.FoamMotion.DRIFTING, LiteLightsOptions.FOAM_MOTION.get(), "a new option starts at its default");

            file.save();
            JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonObject options = saved.getAsJsonObject("options");
            assertFalse(options.get("weather.glass_breath").getAsBoolean(), "the removed option's value stays in the file");
            assertEquals("streaks", options.get("weather.glass_style").getAsString(), "and so does this one's");
            assertEquals("texels_32", options.get("weather.glass_detail").getAsString());
            assertFalse(options.get("weather.glass_frost").getAsBoolean());
            assertEquals("drifting", options.get("water.foam_motion").getAsString());
            assertFalse(saved.has("unreadable") && saved.getAsJsonObject("unreadable").size() > 0, "nothing was unreadable");
            // Schema 2 files go through 0.9.7's glint step, which has nothing to move in this one.
            assertTrue(Files.exists(folder.resolve("litelights.json.schema2.bak")), "the file was backed up before the glint step");
        } finally {
            registry.resetAll();
        }
    }
}
