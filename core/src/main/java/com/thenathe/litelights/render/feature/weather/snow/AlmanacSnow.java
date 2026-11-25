package com.thenathe.litelights.render.feature.weather.snow;

import com.thenathe.almanac.api.AlmanacApi;
import com.thenathe.almanac.api.Climate;
import com.thenathe.almanac.api.SeasonClimate;
import com.thenathe.almanac.api.SnowLayers;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Almanac's snow for the visual snow (Almanac's docs/INTEGRATION.md, "Added in 0.3.0"): each base
 * climate's layer shares ({@code snowLayers}) and the season's climate of a biome
 * ({@code seasonClimateOf}). Almanac's client calls are safe from any thread. Loaded only when
 * Almanac 1.0.0 or later is installed ({@code AlmanacVersion.shared}); with core's AlmanacClimate
 * and the far terrain's AlmanacFarSnow, the only classes that name Almanac.
 *
 * <p>A column's snow follows its biome's base climate, the area Almanac keeps its snow by. Where
 * Almanac is silent (another dimension, before the first season sync, a biome without seasons) the
 * biome's own climate answers, with no seasonal shares, as without Almanac.
 */
final class AlmanacSnow implements SnowClimate {
    /** Areas in {@link SnowShares}' order. */
    private static final Climate[] AREAS = {Climate.HOT, Climate.TEMPERATE, Climate.COLD};

    @Override
    public int area(ClientLevel level, Holder<Biome> biome) {
        Optional<SeasonClimate> season = AlmanacApi.seasonClimateOf(level, biome);
        if (season.isEmpty() || season.get().noSeasonalSnow()) {
            return -1;
        }
        Climate base = season.get().base();
        for (int area = 0; area < AREAS.length; area++) {
            if (AREAS[area] == base) {
                return area;
            }
        }
        return -1;
    }

    @Override
    public boolean coldEnough(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return AlmanacApi.seasonClimateOf(level, biome)
            .map(season -> season.coldEnoughToSnow(pos, level.getSeaLevel()))
            .orElseGet(() -> biome.value().coldEnoughToSnow(pos, level.getSeaLevel()));
    }

    @Override
    public SnowShares shares(ClientLevel level) {
        float[][] shares = new float[SnowShares.AREAS][];
        boolean any = false;
        for (int area = 0; area < AREAS.length; area++) {
            Optional<SnowLayers> layers = AlmanacApi.snowLayers(level, AREAS[area]);
            if (layers.isEmpty()) {
                shares[area] = new float[0];
                continue;
            }
            any = true;
            SnowLayers snow = layers.get();
            float[] held = new float[snow.maxLayers()];
            for (int layer = 1; layer <= held.length; layer++) {
                held[layer - 1] = SnowLayerRule.quantize(snow.share(layer));
            }
            shares[area] = held;
        }
        return any ? SnowShares.of(shares) : SnowShares.NONE;
    }
}
