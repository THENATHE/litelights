package com.thenathe.litelights.render.feature.climate;

import com.thenathe.almanac.api.AlmanacApi;
import com.thenathe.almanac.api.AlmanacEvents;
import com.thenathe.almanac.api.Climate;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Almanac installed on the client, through its public API ({@code com.thenathe.almanac.api}, a stable
 * contract; Almanac's docs/INTEGRATION.md). Loaded only when Almanac is installed; the far terrain's
 * snow has a class of its own ({@code farterrain.AlmanacFarSnow}). The rules and the per-biome
 * answers kept for the sub-season are {@link SeasonalClimate}'s; this only asks Almanac.
 *
 * <p>Almanac is asked about the biome the caller already has (0.1.2's biome lookups), so each answer
 * matches the sampling grid it was asked for, also at biome borders. What falls is Almanac's own
 * rule from 0.1.3 ({@code precipitationOf}).
 */
final class AlmanacClimate implements SeasonalClimate.Answers {
    /** Almanac 0.1.3 or later answers what falls itself. */
    private final boolean almanacSaysWhatFalls = AlmanacVersion.atLeast(AlmanacVersion.CHANGE_TICK);

    private AlmanacClimate() {
    }

    /** Called once, when Almanac is installed. */
    static ClimateSource create() {
        SeasonalClimate climate = new SeasonalClimate(new AlmanacClimate());
        AlmanacEvents.SEASON_CHANGED.register((level, change) -> {
            // In singleplayer the server's level fires too; only the client's holds what is drawn. Snow
            // depths change every few hundred ticks while it snows, and LiteLights doesn't read them.
            if (level.isClientSide() && (change.subSeason() || change.rainReach())) {
                climate.seasonChanged();
                LocalClimate.noteChange();
            }
        });
        return climate;
    }

    @Override
    public SeasonalClimate.@Nullable Place placeOf(ClientLevel level, Holder<Biome> biome) {
        Optional<Holder<Biome>> seasonal = AlmanacApi.seasonalBiome(level, biome);
        if (seasonal.isEmpty()) {
            return null;
        }
        // A biome without copies has no climate of Almanac's, and falls as it always does.
        return AlmanacApi.climateOf(level, biome)
            .map(climate -> new SeasonalClimate.Place(seasonal.get(), true, climate.base() == Climate.HOT, climate.rainReaches()))
            .orElseGet(() -> new SeasonalClimate.Place(seasonal.get(), false, false, true));
    }

    @Override
    public Optional<Biome.Precipitation> precipitationOf(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return almanacSaysWhatFalls ? AlmanacApi.precipitationOf(level, biome, pos) : Optional.empty();
    }

    @Override
    public float worldRain(ClientLevel level) {
        return AlmanacApi.worldRainLevel(level);
    }

    @Override
    public float worldThunder(ClientLevel level) {
        return AlmanacApi.worldThunderLevel(level);
    }
}
