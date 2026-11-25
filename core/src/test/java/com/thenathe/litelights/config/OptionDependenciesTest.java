package com.thenathe.litelights.config;

import com.thenathe.litelights.config.LiteLightsOptions.StormType;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4: every option a row's greying rule reads is found, so Sodium's pages can be told to ask
 * again when any of them changes, and the rule can be asked about values not applied yet.
 */
class OptionDependenciesTest {
    enum Density { OFF, NORMAL }

    enum Distance { NEAR, FAR }

    @TempDir
    static Path folder;

    @BeforeAll
    static void loadOptions() {
        if (!LiteLightsConfig.loaded()) {
            LiteLightsConfig.useFile(folder.resolve("litelights.json"));
        }
        LiteLightsConfig.init();
    }

    /** Rows shaped like Particles' Enhanced fire and Distance fill. */
    private static final class Fixture {
        final OptionRegistry registry = new OptionRegistry();
        final EnumOption<Density> density = registry.register(
                EnumOption.builder("test.density", Density.class, Density.NORMAL).at("page", "section").build());
        final BoolOption fire = registry.register(BoolOption.builder("test.fire", true).at("page", "section")
                .enabledWhen(values -> values.get(density) != Density.OFF).build());
        final BoolOption embers = registry.register(BoolOption.builder("test.embers", true).at("page", "section")
                .needs(fire).enabledWhen(values -> values.get(density) != Density.OFF).build());
        final EnumOption<Distance> distance = registry.register(
                EnumOption.builder("test.distance", Distance.class, Distance.NEAR).at("page", "section").build());
        // Density is read only once distance isn't Near, which it is by default.
        final BoolOption fill = registry.register(BoolOption.builder("test.fill", false).at("page", "section")
                .enabledWhen(values -> values.get(distance) != Distance.NEAR && values.get(density) != Density.OFF).build());
    }

    @Test
    void theNeedsSwitchItsOwnRuleAndTheConditionAreAllFound() {
        Fixture fixture = new Fixture();
        assertEquals(Set.of(fixture.fire, fixture.density), fixture.embers.dependencies());
        assertEquals(Set.of(fixture.density), fixture.fire.dependencies());
        assertEquals(Set.of(), fixture.density.dependencies());
    }

    @Test
    void anOptionReadOnlyForSomeValuesOfAnotherIsFoundToo() {
        Fixture fixture = new Fixture();
        assertEquals(Set.of(fixture.distance, fixture.density), fixture.fill.dependencies());
    }

    @Test
    void theRuleCanBeAskedAboutValuesNotAppliedYet() {
        Fixture fixture = new Fixture();
        OptionValues densityOff = new OptionValues() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(Option<T> option) {
                return option == fixture.density ? (T) Density.OFF : option.get();
            }
        };
        assertTrue(fixture.embers.active(), "live, particles are on");
        assertFalse(fixture.embers.active(densityOff), "with particles off waiting to be applied, the row greys");
        assertFalse(fixture.fire.active(densityOff));
        assertEquals(Density.NORMAL, fixture.density.get(), "asking changes nothing");
    }

    /** The real rows: the ones the review named, and no rule anywhere that Sodium would refuse. */
    @Test
    void liteLightsRowsDeclareWhatTheyRead() {
        assertEquals(Set.of(LiteLightsOptions.ENHANCED_FIRE, LiteLightsOptions.PARTICLE_DENSITY),
                LiteLightsOptions.BURN_MARKS.dependencies());
        assertEquals(Set.of(LiteLightsOptions.PARTICLE_DENSITY, LiteLightsOptions.RAIN_MIST),
                LiteLightsOptions.storm(StormType.THUNDERSTORM).clouds().dependencies());
        assertEquals(Set.of(LiteLightsOptions.PARTICLE_DENSITY, LiteLightsOptions.SANDSTORMS),
                LiteLightsOptions.storm(StormType.HABOOB).clouds().dependencies());
        for (Option<?> option : LiteLightsConfig.registry().all()) {
            assertFalse(option.dependencies().contains(option), option.id() + " depends on itself");
            assertFalse(reaches(option, option, new HashSet<>()), option.id() + " is in a dependency cycle");
        }
    }

    private static boolean reaches(Option<?> from, Option<?> target, Set<Option<?>> seen) {
        for (Option<?> dependency : from.dependencies()) {
            if (dependency == target || seen.add(dependency) && reaches(dependency, target, seen)) {
                return true;
            }
        }
        return false;
    }
}
