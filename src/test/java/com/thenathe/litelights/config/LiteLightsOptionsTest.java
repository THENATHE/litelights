package com.thenathe.litelights.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.render.api.RenderFeature;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The built-in options: defaults from the plan, unique ids, and English text for every row. */
class LiteLightsOptionsTest {
    @TempDir
    static Path folder;

    @BeforeAll
    static void loadOptions() {
        if (!LiteLightsConfig.loaded()) {
            LiteLightsConfig.useFile(folder.resolve("litelights.json"));
        }
        LiteLightsConfig.init();
    }

    @Test
    void defaultsMatchThePlan() {
        assertTrue(LiteLightsOptions.PIXEL_LOCK.on());
        assertEquals(LiteLightsOptions.AntiAliasing.OFF, LiteLightsOptions.ANTI_ALIASING.get());
        assertEquals(LiteLightsOptions.Clouds.FANCY, LiteLightsOptions.CLOUDS.get());
        assertEquals(2, LiteLightsOptions.CLOUD_LAYERS.value());
        assertEquals(LiteLightsOptions.SkyPalette.COMPUTED, LiteLightsOptions.SKY_PALETTE.get());
        assertEquals(LiteLightsOptions.FogLook.HAZE, LiteLightsOptions.FOG_LOOK.get());
        assertEquals(2.0F, LiteLightsOptions.SUNBEAM_STRENGTH.value());
        assertEquals(1.5F, LiteLightsOptions.UNDERWATER_SUNBEAM_STRENGTH.value());
        assertEquals(LiteLightsOptions.SunbeamQuality.MEDIUM, LiteLightsOptions.SUNBEAM_QUALITY.get());
        assertTrue(LiteLightsOptions.GLINT_WATER.on());
        assertEquals(LiteLightsOptions.WaterSparkle.GENTLE, LiteLightsOptions.WATER_SPARKLE.get());
        assertTrue(LiteLightsOptions.GLINT_ICE_SNOW.on());
        assertTrue(LiteLightsOptions.GLINT_METAL_GLASS.on());
        assertFalse(LiteLightsOptions.GLINT_SAND_STONE.on());
        assertEquals(LiteLightsOptions.JumpSplash.ADVANCED, LiteLightsOptions.JUMP_SPLASH.get());
        assertEquals(LiteLightsOptions.WaterPalette.ENHANCED, LiteLightsOptions.WATER_PALETTE.get());
        assertEquals(0.5F, LiteLightsOptions.GLINT_WATER_SOFTNESS.value());
        assertEquals(0.5F, LiteLightsOptions.GLINT_WATER_INTENSITY.value());
        assertEquals(0.5F, LiteLightsOptions.GLINT_GROUND_SOFTNESS.value());
        assertEquals(0.5F, LiteLightsOptions.GLINT_GROUND_INTENSITY.value());
        assertEquals(LiteLightsOptions.GradeSchedule.BY_TIME_OF_DAY, LiteLightsOptions.GRADE_SCHEDULE.get());
        assertEquals(192, LiteLightsOptions.SHADOW_REACH.value());
        assertEquals(LiteLightsOptions.ShadowDetail.SHARPEST, LiteLightsOptions.SHADOW_DETAIL.get());
        assertEquals(LiteLightsOptions.ShadowOffset.BALANCED, LiteLightsOptions.SHADOW_OFFSET.get());
        assertFalse(LiteLightsOptions.UNDERWATER_BLUR.on());
        assertEquals(4, LiteLightsOptions.LARGE_WATER_SCALE.value());
        assertTrue(LiteLightsOptions.SWAY_GRASS.on());
        assertTrue(LiteLightsOptions.SWAY_PLANTS.on());
        assertTrue(LiteLightsOptions.SWAY_LEAVES.on());
        assertTrue(LiteLightsOptions.SWAY_LANTERNS.on());
        assertEquals(LiteLightsOptions.PrecipitationLook.LITELIGHTS, LiteLightsOptions.PRECIPITATION.get());
        assertTrue(LiteLightsOptions.STORM_ATMOSPHERE.on());
        assertTrue(LiteLightsOptions.GLASS_WEATHER.on());
        assertEquals(LiteLightsOptions.GlassDetail.TEXELS_16, LiteLightsOptions.GLASS_DETAIL.get());
        assertTrue(LiteLightsOptions.GLASS_FROST.on());
        assertTrue(LiteLightsOptions.GLASS_DUST.on());
        assertEquals(LiteLightsOptions.FoamMotion.DRIFTING, LiteLightsOptions.FOAM_MOTION.get());
        assertTrue(LiteLightsOptions.OCEAN_WAVES.on());
        assertTrue(LiteLightsOptions.RAIN_DRIPS.on());
        assertTrue(LiteLightsOptions.WALL_STREAKS.on());
        assertEquals(10, LiteLightsOptions.OUTLINE_SIZE.value());
        assertEquals(0.8F, LiteLightsOptions.OUTLINE_SATURATION.value());
        assertEquals(0.5F, LiteLightsOptions.OUTLINE_DIFFUSION.value());
        assertTrue(LiteLightsOptions.OUTLINE_WORN_ARMOUR.on());
        assertTrue(LiteLightsOptions.OUTLINE_HIDE_SHIMMER.on());
        assertEquals(0, LiteLightsConfig.registry().changedCount());
    }

    @Test
    void foamMotionIsAChoiceOnTheWaterPage() {
        assertEquals("water.foam_motion", LiteLightsOptions.FOAM_MOTION.id());
        assertEquals(List.of(LiteLightsOptions.FoamMotion.STATIONARY, LiteLightsOptions.FoamMotion.DRIFTING),
            LiteLightsOptions.FOAM_MOTION.choices());
        // No longer Experimental since defaults098, like Ocean waves.
        assertNull(LiteLightsOptions.FOAM_MOTION.tagKey());
        assertNull(LiteLightsOptions.OCEAN_WAVES.tagKey());
        assertEquals(Pages.WATER, LiteLightsOptions.FOAM_MOTION.page());
        assertEquals(Cost.FREE, LiteLightsOptions.FOAM_MOTION.costOf(LiteLightsOptions.FoamMotion.STATIONARY));
        assertEquals(Cost.LOW, LiteLightsOptions.FOAM_MOTION.costOf(LiteLightsOptions.FoamMotion.DRIFTING));
        // It moves the edge foam and the waterfall landing foam, so it greys out only with both off.
        assertTrue(LiteLightsOptions.FOAM_MOTION.active());
        LiteLightsOptions.EDGE_FOAM.set(false);
        assertTrue(LiteLightsOptions.FOAM_MOTION.active());
        LiteLightsOptions.ENHANCED_WATER.set(false);
        assertFalse(LiteLightsOptions.FOAM_MOTION.active());
        LiteLightsOptions.EDGE_FOAM.reset();
        LiteLightsOptions.ENHANCED_WATER.reset();
    }

    @Test
    void shoreRunUpAndWaveFoamGoWithOceanWavesAndDefaultToTodaysLook() {
        assertEquals("water.shore_run_up", LiteLightsOptions.SHORE_RUN_UP.id());
        assertEquals("water.wave_foam", LiteLightsOptions.WAVE_FOAM.id());
        assertEquals(Pages.WATER, LiteLightsOptions.SHORE_RUN_UP.page());
        assertEquals(Pages.WATER, LiteLightsOptions.WAVE_FOAM.page());
        // 1 is the run-up and the foam as Ocean waves drew them before these rows; the shaders
        // treat 1 as no change.
        assertEquals(1.0F, LiteLightsOptions.SHORE_RUN_UP.defaultValue());
        assertEquals(1.0F, LiteLightsOptions.WAVE_FOAM.defaultValue());
        assertEquals(1.0F, LiteLightsOptions.SHORE_RUN_UP.min());
        assertEquals(2.0F, LiteLightsOptions.SHORE_RUN_UP.max());
        assertEquals(0.25F, LiteLightsOptions.WAVE_FOAM.min());
        assertEquals(2.0F, LiteLightsOptions.WAVE_FOAM.max());
        assertEquals(LiteLightsOptions.OCEAN_WAVES, LiteLightsOptions.SHORE_RUN_UP.needs());
        assertEquals(LiteLightsOptions.OCEAN_WAVES, LiteLightsOptions.WAVE_FOAM.needs());
        // Ocean waves is on by default, so both rows start usable, and grey out with it off.
        assertTrue(LiteLightsOptions.SHORE_RUN_UP.active());
        assertTrue(LiteLightsOptions.WAVE_FOAM.active());
        LiteLightsOptions.OCEAN_WAVES.set(false);
        assertFalse(LiteLightsOptions.SHORE_RUN_UP.active());
        assertFalse(LiteLightsOptions.WAVE_FOAM.active());
        LiteLightsOptions.OCEAN_WAVES.reset();
        // The run-up moves in quarter blocks.
        LiteLightsOptions.SHORE_RUN_UP.set(1.6F);
        assertEquals(1.5F, LiteLightsOptions.SHORE_RUN_UP.value(), 1.0E-6);
        LiteLightsOptions.SHORE_RUN_UP.reset();
        LiteLightsOptions.OCEAN_WAVES.reset();
    }

    @Test
    void shoreWaterIsAChoiceThatGoesWithOceanWavesAndStartsAsWater() {
        assertEquals("water.shore_film", LiteLightsOptions.SHORE_FILM.id());
        assertEquals(List.of(LiteLightsOptions.ShoreFilm.WATER, LiteLightsOptions.ShoreFilm.FOAM,
            LiteLightsOptions.ShoreFilm.FOAM_IN_WATER_OUT), LiteLightsOptions.SHORE_FILM.choices());
        // Water is the clear sheet the swash always drew, so nobody's look changes.
        assertEquals(LiteLightsOptions.ShoreFilm.WATER, LiteLightsOptions.SHORE_FILM.defaultValue());
        assertEquals(Pages.WATER, LiteLightsOptions.SHORE_FILM.page());
        assertEquals(LiteLightsOptions.OCEAN_WAVES, LiteLightsOptions.SHORE_FILM.needs());
        assertTrue(LiteLightsOptions.SHORE_FILM.active());
        LiteLightsOptions.OCEAN_WAVES.set(false);
        assertFalse(LiteLightsOptions.SHORE_FILM.active());
        LiteLightsOptions.OCEAN_WAVES.reset();
        // Its row sits with the other Ocean waves rows, right after Shore run-up.
        List<Option<?>> water = LiteLightsConfig.registry().all().stream().filter(option -> Pages.WATER.equals(option.page())).toList();
        assertEquals(water.indexOf(LiteLightsOptions.SHORE_RUN_UP) + 1, water.indexOf(LiteLightsOptions.SHORE_FILM));
    }

    @Test
    void oreSparkleMotionGoesWithTheOreGlintAndStartsGentle() {
        assertEquals("lighting.ore_sparkle", LiteLightsOptions.ORE_SPARKLE.id());
        assertEquals(List.of(LiteLightsOptions.OreSparkle.STILL, LiteLightsOptions.OreSparkle.GENTLE),
            LiteLightsOptions.ORE_SPARKLE.choices());
        assertEquals(LiteLightsOptions.OreSparkle.GENTLE, LiteLightsOptions.ORE_SPARKLE.defaultValue());
        assertEquals(Pages.LIGHTING, LiteLightsOptions.ORE_SPARKLE.page());
        assertEquals(LiteLightsOptions.GLINT_ORES, LiteLightsOptions.ORE_SPARKLE.needs());
        assertTrue(LiteLightsOptions.ORE_SPARKLE.active());
        LiteLightsOptions.GLINT_ORES.set(false);
        assertFalse(LiteLightsOptions.ORE_SPARKLE.active());
        LiteLightsOptions.GLINT_ORES.reset();
        // Its row sits right under Glint on ores and crystals.
        List<Option<?>> lighting = LiteLightsConfig.registry().all().stream().filter(option -> Pages.LIGHTING.equals(option.page())).toList();
        assertEquals(lighting.indexOf(LiteLightsOptions.GLINT_ORES) + 1, lighting.indexOf(LiteLightsOptions.ORE_SPARKLE));
    }

    @Test
    void glassWeatherAndRainDetailsDontDependOnTheRainMode() {
        LiteLightsOptions.PRECIPITATION.set(LiteLightsOptions.PrecipitationLook.VANILLA);
        assertTrue(LiteLightsOptions.GLASS_WEATHER.active());
        assertTrue(LiteLightsOptions.GLASS_FROST.active());
        assertTrue(LiteLightsOptions.RAIN_DRIPS.active());
        assertTrue(LiteLightsOptions.WALL_STREAKS.active());
        LiteLightsOptions.PARTICLE_DENSITY.set(LiteLightsOptions.ParticleDensity.OFF);
        assertFalse(LiteLightsOptions.RAIN_DRIPS.active(), "drips are particles");
        assertTrue(LiteLightsOptions.GLASS_WEATHER.active(), "glass weather is drawn in the shader");
        LiteLightsOptions.PARTICLE_DENSITY.reset();
        LiteLightsOptions.PRECIPITATION.reset();
    }

    @Test
    void everyFeatureStartsReady() {
        for (Option<?> option : LiteLightsConfig.registry().all()) {
            assertTrue(option.feature().state() instanceof RowState.Ready, option.id());
        }
        assertTrue(LiteLightsOptions.ANTI_ALIASING.choiceState(LiteLightsOptions.AntiAliasing.TAA) instanceof RowState.Ready);
        assertTrue(LiteLightsOptions.ANTI_ALIASING.choiceState(LiteLightsOptions.AntiAliasing.FXAA) instanceof RowState.Ready);
    }

    @Test
    void choiceCostsFollowTheChoice() {
        assertEquals(Cost.FREE, LiteLightsOptions.ANTI_ALIASING.currentCost());
        assertEquals(Cost.LOW, LiteLightsOptions.ANTI_ALIASING.costOf(LiteLightsOptions.AntiAliasing.FXAA));
        assertEquals(Cost.MEDIUM, LiteLightsOptions.ANTI_ALIASING.costOf(LiteLightsOptions.AntiAliasing.TAA));
    }

    @Test
    void gradeSlidersShowOneTimeOfDayAtATime() {
        LiteLightsOptions.GRADE_SCHEDULE.set(LiteLightsOptions.GradeSchedule.ALL_DAY);
        assertTrue(LiteLightsOptions.grade(LiteLightsOptions.GradeTime.ALL_DAY).exposure().shown());
        assertFalse(LiteLightsOptions.grade(LiteLightsOptions.GradeTime.DUSK).exposure().shown());
        LiteLightsOptions.GRADE_SCHEDULE.set(LiteLightsOptions.GradeSchedule.BY_TIME_OF_DAY);
        LiteLightsOptions.editGradeTime(LiteLightsOptions.GradeTime.DUSK);
        assertTrue(LiteLightsOptions.grade(LiteLightsOptions.GradeTime.DUSK).exposure().shown());
        assertFalse(LiteLightsOptions.grade(LiteLightsOptions.GradeTime.ALL_DAY).exposure().shown());
        LiteLightsOptions.GRADE_SCHEDULE.reset();
        assertEquals(16, LiteLightsOptions.grade(LiteLightsOptions.GradeTime.NIGHT).sliders().size());
    }

    @Test
    void defaultGradeIsGentleAndNeutralStaysReachable() {
        LiteLightsOptions.ColourGrade dusk = LiteLightsOptions.grade(LiteLightsOptions.GradeTime.DUSK);
        assertTrue(dusk.temperature().defaultValue() > 0.0F, "dusk starts warm");
        assertTrue(dusk.vibrance().defaultValue() > 0.0F);
        assertTrue(LiteLightsOptions.grade(LiteLightsOptions.GradeTime.NIGHT).temperature().defaultValue() < 0.0F, "night starts cool");
        LiteLightsOptions.ColourGrade midday = LiteLightsOptions.grade(LiteLightsOptions.GradeTime.MIDDAY);
        assertEquals(0.0F, midday.exposure().defaultValue());
        assertEquals(0.0F, midday.contrast().defaultValue());
        assertEquals(0.0F, midday.temperature().defaultValue());
        for (FloatOption slider : LiteLightsOptions.grade(LiteLightsOptions.GradeTime.ALL_DAY).sliders()) {
            float neutral = slider.id().endsWith("_strength") ? 1.0F : 0.0F;
            assertEquals(neutral, slider.defaultValue(), slider.id() + " starts neutral in the all-day set");
        }
        // Neutral is still a position on every slider, and setting it counts as a change.
        dusk.temperature().set(0.0F);
        assertEquals(0.0F, dusk.temperature().value());
        assertTrue(dusk.temperature().isChanged());
        dusk.temperature().reset();
        dusk.blueStrength().set(1.0F);
        assertEquals(1.0F, dusk.blueStrength().value());
        dusk.blueStrength().reset();
        assertFalse(dusk.temperature().isChanged());
    }

    @Test
    void subSwitchesFollowTheirMasterSwitch() {
        assertTrue(LiteLightsOptions.PIXEL_LOCK_GLINT.active());
        LiteLightsOptions.PIXEL_LOCK.set(false);
        assertFalse(LiteLightsOptions.PIXEL_LOCK_GLINT.active());
        assertTrue(LiteLightsOptions.PIXEL_LOCK_GLINT.on(), "greying out never changes the value");
        LiteLightsOptions.PIXEL_LOCK.reset();
    }

    @Test
    void everyRowHasEnglishText() throws IOException {
        JsonObject english;
        try (var stream = getClass().getResourceAsStream("/assets/litelights/lang/en_us.json")) {
            assertNotNull(stream, "en_us.json");
            english = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> missing = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Option<?> option : LiteLightsConfig.registry().all()) {
            assertTrue(ids.add(option.id()), "unique id " + option.id());
            List<String> keys = new ArrayList<>(List.of(option.labelKey(), option.descriptionKey(),
                    Pages.captionKey(option.section()), Pages.captionKey(Pages.parentSection(option.section())),
                    "litelights.page." + option.page()));
            if (option.creditKey() != null) {
                keys.add(option.creditKey());
            }
            if (option instanceof EnumOption<?> choice) {
                addChoiceKeys(choice, keys);
            }
            for (String key : keys) {
                if (!english.has(key) && !missing.contains(key)) {
                    missing.add(key);
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    private static <E extends Enum<E>> void addChoiceKeys(EnumOption<E> option, List<String> keys) {
        for (E choice : option.choices()) {
            keys.add(option.choiceKey(choice));
            if (option.tagKey(choice) != null) {
                keys.add(option.tagKey(choice));
            }
        }
    }

    /**
     * Every render feature reports on a row some option really belongs to. Feature.of creates a
     * row for any id, so a mistyped id would otherwise report into a row nobody sees.
     */
    @Test
    void everyRenderFeatureNamesARealRow() {
        Set<String> rows = new HashSet<>();
        for (Option<?> option : LiteLightsConfig.registry().all()) {
            rows.add(option.feature().id());
            if (option instanceof EnumOption<?> choice) {
                addChoiceRows(choice, rows);
            }
        }
        for (RenderFeature feature : RenderFeature.values()) {
            String row = feature.settingsFeature();
            assertTrue(row == null || rows.contains(row), feature + " reports on " + row + ", which no option has");
        }
    }

    private static <E extends Enum<E>> void addChoiceRows(EnumOption<E> option, Set<String> rows) {
        for (E choice : option.choices()) {
            if (option.choiceFeature(choice) != null) {
                rows.add(option.choiceFeature(choice).id());
            }
        }
    }
}
