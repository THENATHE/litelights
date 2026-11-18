package com.thenathe.litelights.render.feature.climate;

import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The climate with Almanac on the client (SeasonalClimate), from made-up answers: while Almanac is
 * silent the biome answers as it does without seasons, and that silence is never kept.
 */
class SeasonalClimateTest {
    private static final BlockPos AT = new BlockPos(0, 70, 0);
    private static Holder<Biome> desert;
    private static Holder<Biome> winterDesert;
    private static Holder<Biome> plains;
    private static Holder<Biome> winterPlains;

    /** Almanac's answers: silent until told otherwise, counting what is asked. */
    private static final class FakeAlmanac implements SeasonalClimate.Answers {
        boolean synced;
        final List<Holder<Biome>> asked = new ArrayList<>();

        @Override
        public SeasonalClimate.@Nullable Place placeOf(ClientLevel level, Holder<Biome> biome) {
            asked.add(biome);
            if (!synced) {
                return null;
            }
            return biome == desert ? new SeasonalClimate.Place(winterDesert, true, true, true)
                : new SeasonalClimate.Place(winterPlains, true, false, true);
        }

        @Override
        public Optional<Biome.Precipitation> precipitationOf(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
            return Optional.empty();
        }

        @Override
        public float worldRain(ClientLevel level) {
            return 1.0f;
        }

        @Override
        public float worldThunder(ClientLevel level) {
            return 0.0f;
        }
    }

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        desert = registry.register(Biomes.DESERT, biome(2.0f, false), RegistrationInfo.BUILT_IN);
        winterDesert = registry.register(copy("almanac:mid_winter/minecraft/desert"), biome(2.0f, true), RegistrationInfo.BUILT_IN);
        plains = registry.register(Biomes.PLAINS, biome(0.8f, true), RegistrationInfo.BUILT_IN);
        winterPlains = registry.register(copy("almanac:mid_winter/minecraft/plains"), biome(0.0f, true), RegistrationInfo.BUILT_IN);
        registry.bindAllTagsToEmpty();
        registry.freeze();
    }

    @Test
    void aSilentAnswerIsNeverKept() {
        FakeAlmanac almanac = new FakeAlmanac();
        SeasonalClimate climate = new SeasonalClimate(almanac);
        assertSame(plains, climate.seasonalBiome(null, plains, AT), "before the first sync: the biome itself");
        // The first sync arrives, whatever its event says: the next question gets the season's answer.
        almanac.synced = true;
        assertSame(winterPlains, climate.seasonalBiome(null, plains, AT));
        assertSame(winterPlains, climate.seasonalBiome(null, plains, AT));
        assertEquals(2, almanac.asked.size(), "a real answer is kept until the season changes");
        climate.seasonChanged();
        climate.seasonalBiome(null, plains, AT);
        assertEquals(3, almanac.asked.size());
    }

    @Test
    void whileAlmanacIsSilentTheBiomeAnswersAsWithoutSeasons() {
        SeasonalClimate climate = new SeasonalClimate(new FakeAlmanac());
        // VanillaClimate's answers, for the biome the caller asked about (no level lookup, so loaded or not).
        assertTrue(climate.dryHeat(null, desert, AT), "a desert is dry and hot");
        assertFalse(climate.dryHeat(null, winterDesert, AT), "a copy with rain, as a client without Almanac would read it");
        assertSame(desert, climate.seasonalBiome(null, desert, AT));
    }

    @Test
    void theSilentAnswersComeFromTheFallbackForTheCallersBiome() {
        List<Holder<Biome>> fallbackAsked = new ArrayList<>();
        ClimateSource fallback = new ClimateSource() {
            @Override
            public String name() {
                return "fallback";
            }

            @Override
            public Biome.Precipitation fallAt(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
                fallbackAsked.add(biome);
                return Biome.Precipitation.RAIN;
            }

            @Override
            public boolean coldEnoughToSnow(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
                fallbackAsked.add(biome);
                return false;
            }

            @Override
            public boolean dryHeat(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
                fallbackAsked.add(biome);
                return false;
            }

            @Override
            public Holder<Biome> seasonalBiome(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
                return biome;
            }
        };
        SeasonalClimate climate = new SeasonalClimate(new FakeAlmanac(), fallback);
        assertEquals(Biome.Precipitation.RAIN, climate.fallAt(null, plains, AT));
        assertFalse(climate.coldEnoughToSnow(null, desert, AT));
        assertFalse(climate.dryHeat(null, desert, AT));
        assertEquals(List.of(plains, desert, desert), fallbackAsked);
    }

    @Test
    void onceSyncedAlmanacsRulesHold() {
        FakeAlmanac almanac = new FakeAlmanac();
        almanac.synced = true;
        SeasonalClimate climate = new SeasonalClimate(almanac);
        assertFalse(climate.dryHeat(null, desert, AT), "a winter desert has rain in its copy");
        assertFalse(climate.dryHeat(null, plains, AT), "plains are never HOT at base");
        assertSame(winterDesert, climate.seasonalBiome(null, desert, AT));
    }

    private static ResourceKey<Biome> copy(String id) {
        return ResourceKey.create(Registries.BIOME, Identifier.parse(id));
    }

    private static Biome biome(float temperature, boolean precipitation) {
        return new Biome.BiomeBuilder()
            .hasPrecipitation(precipitation)
            .temperature(temperature)
            .downfall(0.4f)
            .specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3F76E4).build())
            .mobSpawnSettings(MobSpawnSettings.EMPTY)
            .generationSettings(BiomeGenerationSettings.EMPTY)
            .build();
    }
}
