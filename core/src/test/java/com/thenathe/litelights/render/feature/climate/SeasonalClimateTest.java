package com.thenathe.litelights.render.feature.climate;

import com.mojang.serialization.Lifecycle;
import com.thenathe.litelights.render.feature.particles.BiomeClimate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The climate with Almanac on the client (SeasonalClimate), from made-up answers in the shape of
 * Almanac's {@code SeasonClimate}: while Almanac is silent the biome answers as it does without
 * seasons, and that silence is never kept; once it answers, the season's temperature, cold and
 * dryness hold.
 */
class SeasonalClimateTest {
    private static final BlockPos AT = new BlockPos(0, 70, 0);
    private static Holder<Biome> desert;
    private static Holder<Biome> plains;

    /** A biome's climate this sub-season, as Almanac's climate table would give it. */
    private record Season(float temperature, boolean cold, boolean canFall, boolean baseHot) implements SeasonalClimate.Place {
        @Override
        public boolean coldEnoughToSnow(BlockPos pos, int seaLevel) {
            return cold;
        }
    }

    /** Mid winter: rain in the desert, snow on the plains. */
    private static final Season WINTER_DESERT = new Season(1.2f, false, true, true);
    private static final Season WINTER_PLAINS = new Season(0.0f, true, true, false);
    /** Mid summer: nothing can fall in the desert, nor on the plains (their dry cell). */
    private static final Season SUMMER_DESERT = new Season(2.0f, false, false, true);
    private static final Season SUMMER_PLAINS = new Season(1.2f, false, false, false);

    /** Almanac's answers: silent until told otherwise, counting what is asked. */
    private static final class FakeAlmanac implements SeasonalClimate.Answers {
        boolean synced;
        boolean summer;
        Optional<Biome.Precipitation> falls = Optional.empty();
        final List<Holder<Biome>> asked = new ArrayList<>();

        @Override
        public SeasonalClimate.@Nullable Place placeOf(ClientLevel level, Holder<Biome> biome) {
            asked.add(biome);
            if (!synced) {
                return null;
            }
            if (biome == desert) {
                return summer ? SUMMER_DESERT : WINTER_DESERT;
            }
            return summer ? SUMMER_PLAINS : WINTER_PLAINS;
        }

        @Override
        public Optional<Biome.Precipitation> precipitationOf(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
            return falls;
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
        plains = registry.register(Biomes.PLAINS, biome(0.8f, true), RegistrationInfo.BUILT_IN);
        registry.bindAllTagsToEmpty();
        registry.freeze();
    }

    @Test
    void aSilentAnswerIsNeverKept() {
        FakeAlmanac almanac = new FakeAlmanac();
        SeasonalClimate climate = new SeasonalClimate(almanac);
        assertEquals(0.8f, climate.temperature(null, plains, AT), "before the first sync: the biome's own");
        // The first sync arrives, whatever its event says: the next question gets the season's answer.
        almanac.synced = true;
        assertEquals(0.0f, climate.temperature(null, plains, AT));
        assertEquals(0.0f, climate.temperature(null, plains, AT));
        assertEquals(2, almanac.asked.size(), "a real answer is kept until the season changes");
        climate.seasonChanged();
        climate.temperature(null, plains, AT);
        assertEquals(3, almanac.asked.size());
    }

    @Test
    void almanacSharesTheRainOutTheBiomesOwnClimateDoesnt() {
        assertTrue(new SeasonalClimate(new FakeAlmanac()).sharesRainOut(), "with Almanac the clouds get no forecast");
        assertFalse(new VanillaClimate().sharesRainOut());
        assertFalse(new SeasonsModClimate().sharesRainOut());
    }

    @Test
    void whileAlmanacIsSilentTheBiomeAnswersAsWithoutSeasons() {
        SeasonalClimate climate = new SeasonalClimate(new FakeAlmanac());
        // VanillaClimate's answers, for the biome the caller asked about (no level lookup, so loaded or not).
        assertTrue(climate.dryHeat(null, desert, AT), "a desert is dry and hot");
        assertEquals(2.0f, climate.temperature(null, desert, AT));
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
        };
        SeasonalClimate climate = new SeasonalClimate(new FakeAlmanac(), fallback);
        assertEquals(Biome.Precipitation.RAIN, climate.fallAt(null, plains, AT));
        assertFalse(climate.coldEnoughToSnow(null, desert, AT));
        assertFalse(climate.dryHeat(null, desert, AT));
        assertEquals(List.of(plains, desert, desert), fallbackAsked);
    }

    @Test
    void whatFallsIsAlmanacsOwnAnswer() {
        FakeAlmanac almanac = new FakeAlmanac();
        almanac.synced = true;
        almanac.falls = Optional.of(Biome.Precipitation.SNOW);
        SeasonalClimate climate = new SeasonalClimate(almanac);
        assertEquals(Biome.Precipitation.SNOW, climate.fallAt(null, plains, AT));
        assertTrue(almanac.asked.isEmpty(), "what falls needs no place of LiteLights' own");
    }

    @Test
    void onceSyncedTheSeasonsClimateHolds() {
        FakeAlmanac almanac = new FakeAlmanac();
        almanac.synced = true;
        SeasonalClimate climate = new SeasonalClimate(almanac);
        assertFalse(climate.dryHeat(null, desert, AT), "a winter desert has rain");
        assertFalse(climate.dryHeat(null, plains, AT), "plains are never HOT at base");
        almanac.summer = true;
        climate.seasonChanged();
        assertTrue(climate.dryHeat(null, desert, AT), "a desert in mid summer: dry and hot, a sandstorm's place");
        assertFalse(climate.dryHeat(null, plains, AT), "plains in their dry cell have no sand to blow");
    }

    @Test
    void theKindOfPlaceTakesTheSeasonsTemperature() {
        FakeAlmanac almanac = new FakeAlmanac();
        almanac.synced = true;
        SeasonalClimate climate = new SeasonalClimate(almanac);
        assertEquals(BiomeClimate.SNOWY, BiomeClimate.of(plains, climate.temperature(null, plains, AT)), "plains under winter snow");
        assertEquals(BiomeClimate.DESERT, BiomeClimate.of(desert, climate.temperature(null, desert, AT)),
            "still a desert, though it rains in winter");
        assertEquals(BiomeClimate.TEMPERATE, BiomeClimate.of(plains, plains.value().getBaseTemperature()), "without seasons");
    }

    @Test
    void withoutSeasonsSandyLandIsAlwaysDryAndHot() {
        VanillaClimate vanilla = new VanillaClimate();
        assertTrue(vanilla.dryHeat(null, desert, null));
        assertTrue(vanilla.dryHeat(null, plains, null), "the land decides where the sand is, not the climate");
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
