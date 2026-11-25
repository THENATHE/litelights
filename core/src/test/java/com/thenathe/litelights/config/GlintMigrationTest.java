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
 * 0.9.7's glint step (followb097): a Glint softness the player changed carries over to Water glint
 * softness, at the place that looks the same; one left at the old default gives the new defaults.
 */
class GlintMigrationTest {
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

    private static JsonObject migrated(String options) {
        JsonObject root = JsonParser.parseString("{\"schema\": 2, \"options\": {" + options + "}}").getAsJsonObject();
        assertEquals(3, ConfigMigrations.standard().migrate(root, 2));
        return root.getAsJsonObject("options");
    }

    @Test
    void aChangedSoftnessBecomesTheWaterSoftness() {
        JsonObject options = migrated("\"lighting.glint_softness\": 0.8, \"lighting.glint_water\": false");
        assertEquals(0.5f, options.get("lighting.glint_water_softness").getAsFloat(), 1.0e-6f, "0.80 is the new default place");
        assertEquals(0.8f, options.get("lighting.glint_softness").getAsFloat(), 1.0e-6f, "the old value stays in the file");
        assertFalse(options.get("lighting.glint_water").getAsBoolean(), "everything else is left as it was");
        assertFalse(options.has("lighting.glint_ground_softness"), "the ground starts at its own default");
        assertFalse(options.has("lighting.glint_water_intensity"));

        assertEquals(0.0f, migrated("\"lighting.glint_softness\": 0.0").get("lighting.glint_water_softness").getAsFloat(), 1.0e-6f);
        assertEquals(0.25f, migrated("\"lighting.glint_softness\": 0.4").get("lighting.glint_water_softness").getAsFloat(), 1.0e-6f);
        float softest = migrated("\"lighting.glint_softness\": 1.0").get("lighting.glint_water_softness").getAsFloat();
        assertEquals(1.0f, GlintScale.waterSoftness(softest), 0.015f, "the old softest lands where the new slider looks the same");
    }

    @Test
    void theOldDefaultTakesTheNewDefaults() {
        JsonObject options = migrated("\"lighting.glint_softness\": 0.15");
        assertFalse(options.has("lighting.glint_water_softness"));
        assertFalse(migrated("\"lighting.glint_water\": true").has("lighting.glint_water_softness"), "no old softness, nothing to do");
        assertFalse(migrated("\"lighting.glint_softness\": \"soft\"").has("lighting.glint_water_softness"), "an unreadable value is left alone");
    }

    @Test
    void aWaterSoftnessAlreadyThereIsKept() {
        JsonObject options = migrated("\"lighting.glint_softness\": 0.6, \"lighting.glint_water_softness\": 0.9");
        assertEquals(0.9f, options.get("lighting.glint_water_softness").getAsFloat(), 1.0e-6f);
    }

    @Test
    void anUpgradedFileLoadsTheCarriedSoftnessAndKeepsTheOldOne() throws IOException {
        Path path = folder.resolve("litelights.json");
        // As 0.9.6 wrote it, with the player's softer glint.
        Files.writeString(path, """
                {
                  "schema": 2,
                  "options": {
                    "lighting.glint_water": true,
                    "lighting.glint_softness": 0.8,
                    "lighting.glint_metal_glass": false
                  }
                }""");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            ConfigFile file = new ConfigFile(path, registry, ConfigMigrations.standard());
            file.load();
            assertEquals(0.5f, LiteLightsOptions.GLINT_WATER_SOFTNESS.value(), 1.0e-6f);
            assertEquals(0.5f, LiteLightsOptions.GLINT_WATER_INTENSITY.value(), 1.0e-6f);
            assertEquals(0.5f, LiteLightsOptions.GLINT_GROUND_SOFTNESS.value(), 1.0e-6f);
            assertEquals(0.5f, LiteLightsOptions.GLINT_GROUND_INTENSITY.value(), 1.0e-6f);
            assertFalse(LiteLightsOptions.GLINT_METAL_GLASS.on());
            assertTrue(Files.exists(folder.resolve("litelights.json.schema2.bak")), "the 0.9.6 file is backed up first");

            file.save();
            JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            assertEquals(3, saved.get("schema").getAsInt());
            JsonObject options = saved.getAsJsonObject("options");
            assertEquals(0.8f, options.get("lighting.glint_softness").getAsFloat(), 1.0e-6f, "the old value is never dropped");
            assertEquals(0.5f, options.get("lighting.glint_water_softness").getAsFloat(), 1.0e-6f);

            // Loading the upgraded file again changes nothing.
            registry.resetAll();
            file.load();
            assertEquals(0.5f, LiteLightsOptions.GLINT_WATER_SOFTNESS.value(), 1.0e-6f);
        } finally {
            registry.resetAll();
        }
    }

    @Test
    void anUpgradedFileWithTheOldDefaultLoadsTheNewDefaults() throws IOException {
        Path path = folder.resolve("litelights.json");
        Files.writeString(path, "{\"schema\": 2, \"options\": {\"lighting.glint_softness\": 0.15}}");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            new ConfigFile(path, registry, ConfigMigrations.standard()).load();
            assertFalse(LiteLightsOptions.GLINT_WATER_SOFTNESS.isChanged());
            assertFalse(LiteLightsOptions.GLINT_GROUND_INTENSITY.isChanged());
        } finally {
            registry.resetAll();
        }
    }
}
