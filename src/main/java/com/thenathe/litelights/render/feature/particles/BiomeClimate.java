package com.thenathe.litelights.render.feature.particles;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBiomeTags;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.jspecify.annotations.Nullable;

/**
 * The few kinds of place the weather and ambient effects care about. Vanilla biomes are matched by
 * key, modded ones first by the Conventional tags ({@code c:}) in the same order as sky-fog's
 * {@link com.thenathe.litelights.render.feature.skyfog.BiomeAtmospheres} picks a family, so the particles
 * and the fog agree on a modded biome, then by vanilla's tags and temperature.
 */
public enum BiomeClimate {
    TEMPERATE,
    FOREST,
    JUNGLE,
    DESERT,
    BADLANDS,
    SAVANNA,
    SNOWY,
    SWAMP,
    PALE_GARDEN,
    /** Windswept hills and mountain peaks: wind carries grit here in any weather. */
    WINDY,
    LUSH_CAVES,
    MUSHROOM,
    OCEAN,
    BASALT_DELTAS,
    CRIMSON_FOREST,
    WARPED_FOREST,
    SOUL_SAND_VALLEY,
    NETHER_WASTES,
    END;

    private static final BiomeClimate[] VALUES = values();

    public static BiomeClimate byOrdinal(int ordinal) {
        return VALUES[ordinal];
    }

    public static BiomeClimate of(Holder<Biome> biome) {
        if (biome.is(Biomes.DESERT)) {
            return DESERT;
        }
        if (biome.is(Biomes.PALE_GARDEN)) {
            return PALE_GARDEN;
        }
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) {
            return SWAMP;
        }
        if (biome.is(Biomes.LUSH_CAVES)) {
            return LUSH_CAVES;
        }
        if (biome.is(Biomes.MUSHROOM_FIELDS)) {
            return MUSHROOM;
        }
        if (biome.is(Biomes.BASALT_DELTAS)) {
            return BASALT_DELTAS;
        }
        if (biome.is(Biomes.CRIMSON_FOREST)) {
            return CRIMSON_FOREST;
        }
        if (biome.is(Biomes.WARPED_FOREST)) {
            return WARPED_FOREST;
        }
        if (biome.is(Biomes.SOUL_SAND_VALLEY)) {
            return SOUL_SAND_VALLEY;
        }
        if (biome.is(BiomeTags.IS_NETHER)) {
            return NETHER_WASTES;
        }
        if (biome.is(BiomeTags.IS_END)) {
            return END;
        }
        if (!isVanilla(biome)) {
            BiomeClimate tagged = byConventionalTags(biome);
            if (tagged != null) {
                return tagged;
            }
        }
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            return BADLANDS;
        }
        if (biome.is(BiomeTags.IS_SAVANNA)) {
            return biome.is(Biomes.WINDSWEPT_SAVANNA) ? WINDY : SAVANNA;
        }
        float temperature = biome.value().getBaseTemperature();
        if (biome.is(Biomes.WINDSWEPT_HILLS) || biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS) || biome.is(Biomes.WINDSWEPT_FOREST)
            || biome.is(Biomes.STONY_PEAKS) || biome.is(Biomes.JAGGED_PEAKS) || biome.is(Biomes.FROZEN_PEAKS)) {
            return WINDY;
        }
        if (temperature <= 0.15f) {
            return SNOWY;
        }
        // Meadows and cherry groves are tagged as mountains but are sheltered, flowery places.
        if (biome.is(BiomeTags.IS_MOUNTAIN) && !biome.is(Biomes.MEADOW) && !biome.is(Biomes.CHERRY_GROVE)) {
            return WINDY;
        }
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) {
            return OCEAN;
        }
        if (biome.is(BiomeTags.IS_JUNGLE)) {
            return JUNGLE;
        }
        if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA) || biome.is(Biomes.DARK_FOREST)) {
            return FOREST;
        }
        // A modded biome that is hot and dry but not tagged: treat it as a desert.
        if (temperature >= 1.95f && !biome.value().hasPrecipitation()) {
            return DESERT;
        }
        return TEMPERATE;
    }

    private static boolean isVanilla(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.identifier().getNamespace().equals("minecraft")).orElse(false);
    }

    private static @Nullable BiomeClimate byConventionalTags(Holder<Biome> biome) {
        if (biome.is(ConventionalBiomeTags.IS_SWAMP)) {
            return SWAMP;
        }
        if (biome.is(ConventionalBiomeTags.IS_DESERT)) {
            return DESERT;
        }
        if (biome.is(ConventionalBiomeTags.IS_BADLANDS)) {
            return BADLANDS;
        }
        if (biome.is(ConventionalBiomeTags.IS_SAVANNA)) {
            return biome.is(ConventionalBiomeTags.IS_WINDSWEPT) ? WINDY : SAVANNA;
        }
        if (biome.is(ConventionalBiomeTags.IS_MUSHROOM)) {
            return MUSHROOM;
        }
        if (biome.is(ConventionalBiomeTags.IS_JUNGLE)) {
            return JUNGLE;
        }
        if (biome.is(ConventionalBiomeTags.IS_SNOWY) || biome.is(ConventionalBiomeTags.IS_ICY)) {
            return SNOWY;
        }
        if (biome.is(ConventionalBiomeTags.IS_OCEAN) || biome.is(ConventionalBiomeTags.IS_RIVER) || biome.is(ConventionalBiomeTags.IS_BEACH)) {
            return OCEAN;
        }
        if (biome.is(ConventionalBiomeTags.IS_DARK_FOREST) || biome.is(ConventionalBiomeTags.IS_TAIGA) || biome.is(ConventionalBiomeTags.IS_FOREST)) {
            return FOREST;
        }
        if (biome.is(ConventionalBiomeTags.IS_MOUNTAIN_PEAK) || biome.is(ConventionalBiomeTags.IS_WINDSWEPT)
            || biome.is(ConventionalBiomeTags.IS_MOUNTAIN)) {
            return WINDY;
        }
        return null;
    }

    /** Where sand lies loose: wind and storms lift it. */
    public boolean isSandy() {
        return this == DESERT || this == BADLANDS;
    }
}
