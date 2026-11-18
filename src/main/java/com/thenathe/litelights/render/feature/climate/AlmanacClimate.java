package com.thenathe.litelights.render.feature.climate;

import com.thenathe.almanac.api.AlmanacApi;
import com.thenathe.almanac.api.AlmanacEvents;
import com.thenathe.almanac.api.Climate;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Almanac installed on the client, through its public API ({@code com.thenathe.almanac.api}, a stable
 * contract from 0.1.1; Almanac's docs/INTEGRATION.md). Loaded only when Almanac is installed; the far
 * terrain's snow has a class of its own ({@code farterrain.AlmanacFarSnow}).
 *
 * <p>Per real biome it keeps what Almanac says holds there this sub-season: the sub-season's copy of
 * the biome (whose temperature and precipitation are the season's), the biome's own climate, and
 * whether the current rain event reaches it. Almanac's season-changed event drops all of it. Where
 * Almanac has nothing to say (outside the overworld, or before the server's first season sync) the
 * game's hooked lookups answer, as with any seasons mod.
 *
 * <p>Almanac is asked about the biome the caller already has (0.1.2's biome lookups), so each answer
 * matches the sampling grid it was asked for, also at biome borders. What falls is Almanac's own
 * rule from 0.1.3 ({@code precipitationOf}); with 0.1.2 it is worked out here the same way.
 */
final class AlmanacClimate implements ClimateSource {
    /**
     * What holds for one real biome this sub-season; {@code base} (the biome's own climate) is null
     * for a biome Almanac makes no copies of, and {@code seasonal} only for {@link #SILENT}.
     */
    private record Place(@Nullable Holder<Biome> seasonal, @Nullable Climate base, boolean reached) {
    }

    /** Almanac has nothing to say about the biome: outside the overworld, or before the first season sync. */
    private static final Place SILENT = new Place(null, null, false);

    private final ClimateSource hooked = new SeasonsModClimate();
    private final Map<Biome, Place> places = new IdentityHashMap<>();
    /** Almanac 0.1.3 or later answers what falls itself. */
    private final boolean almanacSaysWhatFalls = AlmanacVersion.atLeast(AlmanacVersion.CHANGE_TICK);

    private AlmanacClimate() {
    }

    /** Called once, when Almanac is installed. */
    static ClimateSource create() {
        AlmanacClimate source = new AlmanacClimate();
        AlmanacEvents.SEASON_CHANGED.register((level, change) -> {
            // In singleplayer the server's level fires too; only the client's holds what is drawn. Snow
            // depths change every few hundred ticks while it snows, and LiteLights doesn't read them.
            if (level.isClientSide() && (change.subSeason() || change.rainReach())) {
                source.places.clear();
                LocalClimate.noteChange();
            }
        });
        return source;
    }

    @Override
    public String name() {
        return "Almanac";
    }

    @Override
    public Biome.Precipitation fallAt(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        if (almanacSaysWhatFalls) {
            Optional<Biome.Precipitation> falls = AlmanacApi.precipitationOf(level, biome, pos);
            if (falls.isPresent()) {
                return falls.get();
            }
        }
        Place place = placeOf(level, biome);
        if (place == SILENT) {
            return hooked.fallAt(level, biome, pos);
        }
        // Almanac 0.1.2's rule: nothing where the event doesn't reach the climate, the copy's precipitation where it does.
        return place.reached ? place.seasonal.value().getPrecipitationAt(pos, level.getSeaLevel()) : Biome.Precipitation.NONE;
    }

    @Override
    public boolean coldEnoughToSnow(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        Place place = placeOf(level, biome);
        return place == SILENT ? hooked.coldEnoughToSnow(level, biome, pos) : place.seasonal.value().coldEnoughToSnow(pos, level.getSeaLevel());
    }

    /**
     * A HOT biome (Almanac's deserts and badlands) in a sub-season where nothing can fall there, as
     * Almanac's integration guide puts it: never a desert in winter, where Almanac brings rain, and
     * never a plain or a beach in mid summer, which turn HOT and dry for the season but have no sand
     * to blow. "Can fall" rather than "falls now": where the event just doesn't reach, Almanac clears
     * that player's sky, and a sandstorm would blow under it.
     */
    @Override
    public boolean dryHeat(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        Place place = placeOf(level, biome);
        if (place == SILENT || place.base == null) {
            return hooked.dryHeat(level, biome, pos);
        }
        return place.base == Climate.HOT && !place.seasonal.value().hasPrecipitation();
    }

    @Override
    public Holder<Biome> seasonalBiome(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        Place place = placeOf(level, biome);
        return place == SILENT ? biome : place.seasonal;
    }

    /** Almanac sends the dimension's own levels too (0.1.2): each player's rain is only their share. */
    @Override
    public float worldRain(ClientLevel level) {
        return AlmanacApi.worldRainLevel(level);
    }

    @Override
    public float worldThunder(ClientLevel level) {
        return AlmanacApi.worldThunderLevel(level);
    }

    @Override
    public void forget() {
        places.clear();
    }

    private Place placeOf(ClientLevel level, Holder<Biome> biome) {
        Place place = places.get(biome.value());
        if (place == null) {
            place = lookUp(level, biome);
            places.put(biome.value(), place);
        }
        return place;
    }

    private static Place lookUp(ClientLevel level, Holder<Biome> biome) {
        Optional<Holder<Biome>> seasonal = AlmanacApi.seasonalBiome(level, biome);
        if (seasonal.isEmpty()) {
            return SILENT;
        }
        // A biome without copies has no climate of Almanac's, and falls as it always does.
        return AlmanacApi.climateOf(level, biome)
            .map(climate -> new Place(seasonal.get(), climate.base(), climate.rainReaches()))
            .orElseGet(() -> new Place(seasonal.get(), null, true));
    }
}
