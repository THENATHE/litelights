package com.thenathe.litelights.config;

import com.thenathe.litelights.config.FirstSetup.Preset;
import com.thenathe.litelights.config.FirstSetup.Tier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first-time setup's presets, and when the question is asked. */
class FirstSetupTest {
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
    void presetsSetRegisteredOptionsToValuesTheyTake() {
        Set<Option<?>> registered = new HashSet<>(LiteLightsConfig.registry().all());
        Set<Option<?>> seen = new HashSet<>();
        for (Preset<?> preset : FirstSetup.PRESETS) {
            assertTrue(registered.contains(preset.option()), preset.option().id() + " is a registered option");
            assertTrue(seen.add(preset.option()), preset.option().id() + " has one row");
            for (Tier tier : Tier.values()) {
                assertValid(preset, tier);
            }
        }
    }

    private static <T> void assertValid(Preset<T> preset, Tier tier) {
        Option<T> option = preset.option();
        T value = preset.value(tier);
        if (option instanceof EnumOption<?> choice) {
            assertTrue(choice.choices().contains(value), option.id() + " offers " + value);
        }
        try {
            option.set(value);
            assertEquals(value, option.get(), option.id() + " takes " + value + " for " + tier + " as it is");
        } finally {
            option.reset();
        }
    }

    @Test
    void everyAnswerChangesSomethingAndTheTopKeepsTheMistField() {
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            for (Tier tier : Tier.values()) {
                registry.resetAll();
                FirstSetup.choose(tier);
                assertTrue(registry.changedCount() > 0, tier + " changes some settings");
                assertEquals(LiteLightsOptions.MistStyle.FIELD, LiteLightsOptions.MIST_STYLE.get(), "3D puffs stay off in " + tier);
                assertEquals(LiteLightsOptions.SkyPalette.COMPUTED, LiteLightsOptions.SKY_PALETTE.get(), "looks stay at their defaults");
                assertEquals(LiteLightsOptions.WaterPalette.ENHANCED, LiteLightsOptions.WATER_PALETTE.get());
            }
            registry.resetAll();
            FirstSetup.choose(Tier.TOP);
            assertEquals(LiteLightsOptions.ShadowDetail.ULTRA, LiteLightsOptions.SHADOW_DETAIL.get());
            assertEquals(512, LiteLightsOptions.SHADOW_REACH.value());
            registry.resetAll();
            FirstSetup.choose(Tier.INTEGRATED);
            assertEquals(LiteLightsOptions.ShadowDetail.FASTEST, LiteLightsOptions.SHADOW_DETAIL.get());
            assertTrue(LiteLightsOptions.WORLD_SHADOWS.on(), "the lowest answer keeps shadows");
            assertTrue(LiteLightsOptions.WATER_MIRRORS.on(), "and reflections");
            assertEquals(LiteLightsOptions.ParticleDensity.SPARSE, LiteLightsOptions.PARTICLE_DENSITY.get(), "particles thinned, not off");
        } finally {
            registry.resetAll();
        }
    }

    @Test
    void theCreatorsRecommendedChangesNothing() {
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            registry.resetAll();
            FirstSetup.useRecommended();
            assertEquals(0, registry.changedCount());
            // Run again after an answer, it puts the preset settings back.
            FirstSetup.choose(Tier.INTEGRATED);
            FirstSetup.useRecommended();
            assertEquals(0, registry.changedCount());
            FirstSetup.dismiss();
            assertEquals(0, registry.changedCount());
        } finally {
            registry.resetAll();
        }
    }

    @Test
    void askedOnlyOnAFreshConfigAndNotOnceAnswered() throws IOException {
        Path path = folder.resolve("litelights.json");
        OptionRegistry registry = LiteLightsConfig.registry();
        try {
            ConfigFile fresh = new ConfigFile(path, registry, ConfigMigrations.standard());
            fresh.load();
            assertTrue(FirstSetup.due(fresh), "a fresh install is asked");
            FirstSetup.markDone(fresh);
            assertFalse(FirstSetup.due(fresh), "not again once answered");
            fresh.save();

            ConfigFile next = new ConfigFile(path, registry, ConfigMigrations.standard());
            next.load();
            assertFalse(FirstSetup.due(next), "not on the next start");
            assertTrue(FirstSetup.done(next), "the answer is remembered in the file");
            next.save();
            ConfigFile after = new ConfigFile(path, registry, ConfigMigrations.standard());
            after.load();
            assertTrue(FirstSetup.done(after), "and kept through another save");

            Path upgraded = folder.resolve("upgraded.json");
            Files.writeString(upgraded, """
                    { "schema": 3, "options": { "shadows.reach": 256 } }""");
            ConfigFile upgrade = new ConfigFile(upgraded, registry, ConfigMigrations.standard());
            upgrade.load();
            assertFalse(FirstSetup.due(upgrade), "a player upgrading from an earlier version isn't asked");

            Path broken = folder.resolve("broken.json");
            Files.writeString(broken, "{ not json");
            ConfigFile unreadable = new ConfigFile(broken, registry, ConfigMigrations.standard());
            unreadable.load();
            assertFalse(FirstSetup.due(unreadable), "nor one whose file couldn't be read");
        } finally {
            registry.resetAll();
        }
    }
}
