package com.thenathe.litelights.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.config.LiteLightsOptions.StormClouds;
import com.thenathe.litelights.config.LiteLightsOptions.StormFog;
import com.thenathe.litelights.config.LiteLightsOptions.StormType;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Weather page as regrouped in 0.1.7 (storms17): its sections and rows in order, each storm
 * type's two rows, and a 0.1.6 file that loads into the new layout without losing anything.
 */
class WeatherPageTest {
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
    void theSectionsComeInTheApprovedOrder() {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (Option<?> option : LiteLightsConfig.registry().onPage(Pages.WEATHER)) {
            sections.computeIfAbsent(Pages.parentSection(option.section()), key -> new ArrayList<>()).add(option.id());
        }
        assertEquals(List.of("rain_snow", "storms", "details", "glass", "events"), List.copyOf(sections.keySet()));
        assertEquals(List.of("weather.precipitation", "weather.density", "weather.distance", "weather.distance_fill", "weather.rain_mist"),
            sections.get("rain_snow"));
        List<String> storms = new ArrayList<>(List.of("weather.storm_atmosphere"));
        for (String type : new String[] {"rain", "thunderstorm", "snow", "blizzard", "sandstorm", "haboob"}) {
            storms.add("weather.storms." + type + ".fog");
            storms.add("weather.storms." + type + ".clouds");
        }
        assertEquals(storms, sections.get("storms"));
        assertEquals(List.of("weather.rain_drips", "weather.wall_streaks", "weather.drifting_sand", "weather.sandstorms"),
            sections.get("details"));
        assertTrue(sections.get("glass").contains("weather.glass"));
        assertEquals(List.of("weather.polar_lights", "weather.meteor_nights", "weather.far_fireworks"), sections.get("events"));
    }

    @Test
    void eachStormTypeHasItsOwnSubsectionAndDefaults() {
        for (StormType type : StormType.values()) {
            LiteLightsOptions.StormRows rows = LiteLightsOptions.storm(type);
            String name = EnumOption.storedName(type);
            assertEquals("storms/" + name, rows.fog().section());
            assertEquals("storms/" + name, rows.clouds().section());
            assertEquals(StormFog.NORMAL, rows.fog().defaultValue());
            assertEquals(StormClouds.FOLLOW_DENSITY, rows.clouds().defaultValue());
            assertEquals(List.of(StormFog.LIGHT, StormFog.NORMAL, StormFog.DENSE, StormFog.VERY_DENSE), rows.fog().choices());
        }
        assertEquals(LiteLightsOptions.RAIN_MIST, LiteLightsOptions.storm(StormType.BLIZZARD).clouds().needs(),
            "rain and snow mist rows need Mist in rain and snow");
        assertEquals(null, LiteLightsOptions.storm(StormType.HABOOB).clouds().needs(), "dust clouds need Sandstorms instead");
    }

    @Test
    void theNewNamesShowWhileTheKeysStay() throws IOException {
        JsonObject english;
        try (var stream = getClass().getResourceAsStream("/assets/litelights/lang/en_us.json")) {
            english = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        assertEquals("weather.polar_lights", LiteLightsOptions.POLAR_LIGHTS.id());
        assertEquals("Aurora borealis", english.get("litelights.option.weather.polar_lights").getAsString());
        assertEquals("Aurora borealis", english.get("litelights.preview.event.aurora").getAsString());
        assertEquals(PreviewWeather.Weather.THUNDER_SANDSTORM, PreviewWeather.Weather.valueOf("THUNDER_SANDSTORM"));
        assertEquals("Haboob", english.get("litelights.preview.weather.thunder_sandstorm").getAsString());
        assertEquals("Haboob", english.get("litelights.section.storms.haboob").getAsString());
        String aside = "Arizona's word for a towering dust storm.";
        assertEquals(aside, english.get("litelights.section.storms.haboob.tooltip").getAsString());
        assertEquals(aside, english.get("litelights.preview.weather.thunder_sandstorm.tooltip").getAsString());
        for (Map.Entry<String, com.google.gson.JsonElement> entry : english.entrySet()) {
            String text = entry.getValue().getAsString();
            assertFalse(text.contains("Polar lights") || text.contains("Thunder sandstorm"), entry.getKey() + " still has the old name");
        }
    }

    @Test
    void aFileFrom016KeepsEveryWeatherValue() throws IOException {
        Path path = folder.resolve("litelights.json");
        // 0.1.6+26.3 as a player who changed every Weather row left it.
        Files.writeString(path, """
                {
                  "schema": 2,
                  "options": {
                    "weather.precipitation": "vanilla_plus",
                    "weather.density": "dense",
                    "weather.rain_mist": false,
                    "weather.storm_atmosphere": false,
                    "weather.rain_drips": false,
                    "weather.wall_streaks": false,
                    "weather.drifting_sand": false,
                    "weather.sandstorms": false,
                    "weather.glass": false,
                    "weather.glass_detail": "texels_32",
                    "weather.glass_frost": false,
                    "weather.glass_dust": false,
                    "weather.polar_lights": false,
                    "weather.meteor_nights": false,
                    "weather.far_fireworks": false,
                    "weather.distance": "very_far",
                    "weather.distance_fill": "fill"
                  }
                }""");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            ConfigFile file = new ConfigFile(path, registry, ConfigMigrations.standard());
            file.load();
            assertEquals(LiteLightsOptions.PrecipitationLook.VANILLA_PLUS, LiteLightsOptions.PRECIPITATION.get());
            assertEquals(LiteLightsOptions.WeatherDensity.DENSE, LiteLightsOptions.WEATHER_DENSITY.get());
            assertFalse(LiteLightsOptions.RAIN_MIST.on());
            assertFalse(LiteLightsOptions.STORM_ATMOSPHERE.on());
            assertFalse(LiteLightsOptions.RAIN_DRIPS.on());
            assertFalse(LiteLightsOptions.WALL_STREAKS.on());
            assertFalse(LiteLightsOptions.DRIFTING_SAND.on());
            assertFalse(LiteLightsOptions.SANDSTORMS.on());
            assertFalse(LiteLightsOptions.GLASS_WEATHER.on());
            assertFalse(LiteLightsOptions.POLAR_LIGHTS.on());
            assertFalse(LiteLightsOptions.METEOR_NIGHTS.on());
            assertFalse(LiteLightsOptions.FAR_FIREWORKS.on());
            assertEquals(LiteLightsOptions.SpawnDistance.VERY_FAR, LiteLightsOptions.WEATHER_DISTANCE.get());
            assertEquals(LiteLightsOptions.DistanceFill.FILL, LiteLightsOptions.WEATHER_DISTANCE_FILL.get());
            for (StormType type : StormType.values()) {
                assertEquals(StormFog.NORMAL, LiteLightsOptions.storm(type).fog().get(), "new rows start at their defaults");
                assertEquals(StormClouds.FOLLOW_DENSITY, LiteLightsOptions.storm(type).clouds().get());
            }

            file.save();
            JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonObject options = saved.getAsJsonObject("options");
            assertFalse(options.get("weather.rain_mist").getAsBoolean(), "Mist in rain and snow is kept as it was");
            assertEquals("dense", options.get("weather.density").getAsString());
            assertEquals("very_far", options.get("weather.distance").getAsString());
            assertFalse(options.get("weather.polar_lights").getAsBoolean());
            assertEquals("normal", options.get("weather.storms.haboob.fog").getAsString());
            assertEquals("follow_density", options.get("weather.storms.rain.clouds").getAsString());
            assertFalse(saved.has("unreadable") && saved.getAsJsonObject("unreadable").size() > 0, "nothing was unreadable");
            assertFalse(Files.exists(folder.resolve("litelights.json.schema2.bak")), "no migration was needed");
        } finally {
            registry.resetAll();
        }
    }
}
