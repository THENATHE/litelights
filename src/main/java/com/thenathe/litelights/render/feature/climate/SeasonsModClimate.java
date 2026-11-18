package com.thenathe.litelights.render.feature.climate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Any seasons mod LiteLights has no API for (Serene Seasons, Fabric Seasons and the like), through
 * the client level's own precipitation lookup: the one vanilla's rain and snow are drawn from, so a
 * seasons mod that makes vanilla's weather follow the season hooks it (or what it reads). LiteLights
 * only knows these mods by their public descriptions and never depends on them.
 */
final class SeasonsModClimate implements ClimateSource {
    @Override
    public String name() {
        return "seasons mod";
    }

    /** The level's lookup reads the biome itself; it says nothing falls in a chunk that isn't loaded. */
    @Override
    public Biome.Precipitation fallAt(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return level.getPrecipitationAt(pos);
    }

    @Override
    public boolean coldEnoughToSnow(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return snowsHere(fallAt(level, biome, pos), biome.value().coldEnoughToSnow(pos, level.getSeaLevel()));
    }

    /** Dry wherever the season lets nothing fall: the sandy biomes as ever, unless the season brings them rain. */
    @Override
    public boolean dryHeat(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return fallAt(level, biome, pos) == Biome.Precipitation.NONE;
    }

    @Override
    public Holder<Biome> seasonalBiome(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return biome;
    }

    /**
     * Cold enough for snow by what falls: the season's word where something does, the biome's own
     * where nothing does (a seasons mod changes what falls, and a dry place has nothing to go by).
     */
    static boolean snowsHere(Biome.Precipitation fall, boolean biomeCold) {
        return switch (fall) {
            case SNOW -> true;
            case RAIN -> false;
            case NONE -> biomeCold;
        };
    }
}
