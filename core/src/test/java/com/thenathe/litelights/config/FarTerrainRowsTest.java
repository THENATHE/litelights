package com.thenathe.litelights.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.render.modules.ModuleRows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The far terrain module's own rows, declared through the internal API (ModuleSettings): they are
 * core options like any other, saved under their ids and read back, with the defaults round 2
 * chose (off, 4 km, Medium, Auto, Auto) and the Experimental tag on the switch.
 */
class FarTerrainRowsTest {
    @TempDir
    static Path configFolder;

    @TempDir
    Path folder;

    @BeforeAll
    static void declare() {
        if (!LiteLightsConfig.loaded()) {
            LiteLightsConfig.useFile(configFolder.resolve("litelights.json"));
        }
        LiteLightsConfig.init();
        ModuleRows.declareFarTerrain();
    }

    private static Map<String, Option<?>> options() {
        return LiteLightsConfig.registry().all().stream().collect(Collectors.toMap(Option::id, Function.identity()));
    }

    @Test
    void rowsAreOnTheFarTerrainPageWithTheirDefaults() {
        Map<String, Option<?>> options = options();
        Option<?> enabled = options.get("far_terrain.enabled");
        assertEquals(false, enabled.defaultValue(), "off by default: today's picture and cost");
        assertEquals("litelights.tag.experimental", enabled.tagKey());
        assertEquals("far_terrain", enabled.page());
        assertEquals("far_terrain", enabled.feature().id());
        assertEquals(Cost.MEDIUM, enabled.cost());
        assertEquals("KM_4", options.get("far_terrain.distance").defaultValue().toString());
        assertEquals("MEDIUM", options.get("far_terrain.detail").defaultValue().toString());
        assertEquals("AUTO", options.get("far_terrain.video_memory").defaultValue().toString());
        assertEquals("AUTO", options.get("far_terrain.threads").defaultValue().toString());
        assertEquals(true, options.get("far_terrain.lights").defaultValue(), "far lights on with far terrain");
        assertEquals("FULL", options.get("far_terrain.water_look").defaultValue().toString(), "far water keeps the near water's look");
        for (String id : new String[] {"far_terrain.distance", "far_terrain.detail", "far_terrain.video_memory", "far_terrain.threads",
            "far_terrain.lights", "far_terrain.water_look"}) {
            assertEquals(enabled, options.get(id).needs(), id + " is greyed out while the switch is off");
        }
    }

    @Test
    void savedValuesLoadBack() throws IOException {
        Path path = folder.resolve("litelights.json");
        Files.writeString(path, """
                {
                  "schema": 3,
                  "options": {
                    "far_terrain.enabled": true,
                    "far_terrain.distance": "km_8",
                    "far_terrain.detail": "high",
                    "far_terrain.video_memory": "gb_1",
                    "far_terrain.threads": "two",
                    "far_terrain.water_look": "plain"
                  }
                }""");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            ConfigFile file = new ConfigFile(path, registry, ConfigMigrations.standard());
            file.load();
            Map<String, Option<?>> options = options();
            assertEquals(true, options.get("far_terrain.enabled").get());
            assertEquals("KM_8", options.get("far_terrain.distance").get().toString());
            assertEquals("HIGH", options.get("far_terrain.detail").get().toString());
            assertEquals("GB_1", options.get("far_terrain.video_memory").get().toString());
            assertEquals("TWO", options.get("far_terrain.threads").get().toString());
            assertEquals("PLAIN", options.get("far_terrain.water_look").get().toString());

            file.save();
            JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("options");
            assertTrue(saved.get("far_terrain.enabled").getAsBoolean());
            assertEquals("km_8", saved.get("far_terrain.distance").getAsString());
            assertEquals("two", saved.get("far_terrain.threads").getAsString());
        } finally {
            registry.resetAll();
        }
    }

    @Test
    void aFileWithoutTheRowsLeavesFarTerrainOff() throws IOException {
        Path path = folder.resolve("litelights.json");
        Files.writeString(path, """
                {
                  "schema": 3,
                  "options": { "shadows.reach": 192 }
                }""");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            new ConfigFile(path, registry, ConfigMigrations.standard()).load();
            assertFalse((Boolean) options().get("far_terrain.enabled").get());
            assertEquals("KM_4", options().get("far_terrain.distance").get().toString());
        } finally {
            registry.resetAll();
        }
    }
}
