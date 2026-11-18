package com.thenathe.litelights.render.feature.climate;

import com.mojang.serialization.Lifecycle;
import com.thenathe.litelights.render.feature.particles.BiomeClimate;
import net.minecraft.SharedConstants;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Almanac's seasonal copies, as a client without Almanac receives them, are read back to their
 * originals, so what LiteLights matches by id still finds them and the season's climate shows.
 */
class SeasonalCopiesTest {
    private static Holder<Biome> plains;
    private static Holder<Biome> winterPlains;
    private static Holder<Biome> desert;
    private static Holder<Biome> winterDesert;
    private static Holder<Biome> summerDesert;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        // The copies' climate as Almanac makes them: winter snow lowers plains to 0.0, a desert's
        // winter rain only turns its precipitation on, and its dry summer is left as it is.
        plains = registry.register(Biomes.PLAINS, biome(0.8f, true), RegistrationInfo.BUILT_IN);
        winterPlains = registry.register(copy("almanac:mid_winter/minecraft/plains"), biome(0.0f, true), RegistrationInfo.BUILT_IN);
        desert = registry.register(Biomes.DESERT, biome(2.0f, false), RegistrationInfo.BUILT_IN);
        winterDesert = registry.register(copy("almanac:mid_winter/minecraft/desert"), biome(2.0f, true), RegistrationInfo.BUILT_IN);
        summerDesert = registry.register(copy("almanac:mid_summer/minecraft/desert"), biome(2.0f, false), RegistrationInfo.BUILT_IN);
        registry.bindAllTagsToEmpty();
        registry.freeze();
    }

    @Test
    void readsTheOriginalOutOfACopysId() {
        assertEquals("minecraft:plains", SeasonalCopies.originalId("almanac:mid_winter/minecraft/plains"));
        assertEquals("terralith:cave/mantle", SeasonalCopies.originalId("almanac:early_summer/terralith/cave/mantle"),
            "the original's path may have slashes of its own");
    }

    @Test
    void anythingElseIsNotACopy() {
        assertNull(SeasonalCopies.originalId("minecraft:plains"));
        assertNull(SeasonalCopies.originalId("almanac:mid_winter/plains"), "no namespace");
        assertNull(SeasonalCopies.originalId("almanac:midwinter/minecraft/plains"), "no such sub-season");
        assertNull(SeasonalCopies.originalId("almanac:calendar"), "Almanac's own other ids");
        assertNull(SeasonalCopies.originalId("almanac:mid_winter/Minecraft/plains"), "not a valid namespace");
    }

    @Test
    void subSeasonsCountFromEarlySpring() {
        assertEquals(0, SeasonalCopies.subSeasonIndex("early_spring"));
        assertEquals(10, SeasonalCopies.subSeasonIndex("mid_winter"));
        assertEquals(11, SeasonalCopies.subSeasonIndex("late_winter"));
        assertEquals(-1, SeasonalCopies.subSeasonIndex("winter"));
        assertEquals(10, SeasonalCopies.subSeasonOf(winterPlains));
        assertEquals(4, SeasonalCopies.subSeasonOf(summerDesert));
        assertEquals(-1, SeasonalCopies.subSeasonOf(plains));
    }

    @Test
    void aCopyIsMatchedByItsOriginalsId() {
        assertEquals(Biomes.PLAINS, SeasonalCopies.originalKey(winterPlains));
        assertEquals(Biomes.DESERT, SeasonalCopies.originalKey(winterDesert));
        assertEquals(Biomes.PLAINS, SeasonalCopies.originalKey(plains), "a real biome keeps its own id");
        assertTrue(SeasonalCopies.isCopy(winterPlains));
        assertFalse(SeasonalCopies.isCopy(plains));
    }

    @Test
    void aCopyIsTheSameKindOfPlaceWithTheSeasonsTemperature() {
        assertEquals(BiomeClimate.DESERT, BiomeClimate.of(winterDesert), "still a desert, though it rains in winter");
        assertEquals(BiomeClimate.DESERT, BiomeClimate.of(summerDesert));
        assertEquals(BiomeClimate.TEMPERATE, BiomeClimate.of(plains));
        assertEquals(BiomeClimate.SNOWY, BiomeClimate.of(winterPlains), "plains under winter snow");
    }

    @Test
    void withoutSeasonsSandyLandIsAlwaysDryAndHot() {
        VanillaClimate vanilla = new VanillaClimate();
        assertTrue(vanilla.dryHeat(null, desert, null));
        assertTrue(vanilla.dryHeat(null, plains, null), "the land decides where the sand is, not the climate");
    }

    @Test
    void aDesertCopyIsDryOnlyInItsDrySeason() {
        VanillaClimate vanilla = new VanillaClimate();
        assertTrue(vanilla.dryHeat(null, summerDesert, null));
        assertFalse(vanilla.dryHeat(null, winterDesert, null), "winter brings the desert rain");
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
