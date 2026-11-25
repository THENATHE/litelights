package com.thenathe.litelights.render.feature.climate;

import com.thenathe.almanac.api.AlmanacApi;
import com.thenathe.almanac.api.AlmanacEvents;
import com.thenathe.almanac.api.Climate;
import com.thenathe.almanac.api.SeasonClimate;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Almanac installed on the client, through its public API ({@code com.thenathe.almanac.api};
 * Almanac's docs/INTEGRATION.md). Loaded only when Almanac 1.0.0 or later is installed
 * ({@link AlmanacVersion#shared}); the far terrain's snow has a class of its own in the far terrain
 * module ({@code farterrain.AlmanacFarSnow}). The rules and the per-biome answers kept for the
 * sub-season are {@link SeasonalClimate}'s; this only asks Almanac.
 *
 * <p>Almanac is asked about the biome the caller already has, so each answer matches the sampling
 * grid it was asked for, also at biome borders: the season's climate of the biome
 * ({@code seasonClimateOf}) and what falls there ({@code precipitationOf}).
 */
final class AlmanacClimate implements SeasonalClimate.Answers {
    private AlmanacClimate() {
    }

    /** Called once, when Almanac is installed. */
    static ClimateSource create() {
        SeasonalClimate climate = new SeasonalClimate(new AlmanacClimate());
        AlmanacEvents.SEASON_CHANGED.register((level, change) -> {
            // In singleplayer the server's level fires too; only the client's holds what is drawn. Snow
            // depths change every few hundred ticks while it snows, and LiteLights doesn't read them.
            // A new climate table arrives as a change of sub-season.
            if (level.isClientSide() && (change.subSeason() || change.rainReach())) {
                climate.seasonChanged();
                LocalClimate.noteChange();
            }
        });
        return climate;
    }

    @Override
    public SeasonalClimate.@Nullable Place placeOf(ClientLevel level, Holder<Biome> biome) {
        return AlmanacApi.seasonClimateOf(level, biome).map(Season::new).orElse(null);
    }

    @Override
    public Optional<Biome.Precipitation> precipitationOf(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return AlmanacApi.precipitationOf(level, biome, pos);
    }

    @Override
    public float worldRain(ClientLevel level) {
        return AlmanacApi.worldRainLevel(level);
    }

    @Override
    public float worldThunder(ClientLevel level) {
        return AlmanacApi.worldThunderLevel(level);
    }

    /** A biome's climate this sub-season, as Almanac's climate table has it. */
    private record Season(SeasonClimate climate) implements SeasonalClimate.Place {
        @Override
        public float temperature() {
            return climate.temperature();
        }

        @Override
        public boolean coldEnoughToSnow(BlockPos pos, int seaLevel) {
            return climate.coldEnoughToSnow(pos, seaLevel);
        }

        @Override
        public boolean canFall() {
            return climate.canFall();
        }

        @Override
        public boolean baseHot() {
            return climate.base() == Climate.HOT;
        }
    }
}
