package com.thenathe.litelights.render.feature.climate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * The biome's own all-year climate, as LiteLights has always asked it. Also right with Almanac on
 * the server only: the client then receives seasonal copies whose climate is already the season's.
 */
final class VanillaClimate implements ClimateSource {
    @Override
    public String name() {
        return "vanilla";
    }

    @Override
    public Biome.Precipitation fallAt(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return biome.value().getPrecipitationAt(pos, level.getSeaLevel());
    }

    @Override
    public boolean coldEnoughToSnow(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return biome.value().coldEnoughToSnow(pos, level.getSeaLevel());
    }

    /**
     * Always, as before: without seasons only the land decides, and the sandy biomes never rain.
     * A seasonal copy has its season's climate, and one with rain (a desert in winter) isn't dry.
     */
    @Override
    public boolean dryHeat(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return !SeasonalCopies.isCopy(biome) || !biome.value().hasPrecipitation();
    }

    @Override
    public Holder<Biome> seasonalBiome(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return biome;
    }
}
