package com.thenathe.litelights.render.feature.climate;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * The climate with Almanac installed on the client: Almanac's answers per biome ({@link AlmanacClimate}
 * asks its API for them), kept for the sub-season, and the rules LiteLights draws its weather by.
 * Names nothing of Almanac's, so the rules can be tested without it.
 *
 * <p>Per real biome it keeps what Almanac says holds there this sub-season: the sub-season's copy of
 * the biome (whose temperature and precipitation are the season's), whether the biome is hot all
 * year, and whether the current rain event reaches it. {@link #seasonChanged} drops all of it.
 *
 * <p>Where Almanac has nothing to say (outside the overworld, before the server's first season
 * sync, or on a server without Almanac) the biome answers as it does without a seasons mod
 * ({@link VanillaClimate}), for the biome the caller asked about, loaded or not. Such a silent answer
 * is never kept, so the first sync is seen whatever its event says.
 */
final class SeasonalClimate implements ClimateSource {
    /**
     * What holds for one real biome this sub-season. {@code hasClimate} is false for a biome Almanac
     * makes no copies of (it falls as it always does); {@code baseHot} is its all-year climate being
     * Almanac's HOT (the deserts and badlands).
     */
    record Place(Holder<Biome> seasonal, boolean hasClimate, boolean baseHot, boolean reached) {
    }

    /** Almanac's answers, as asked through its API. */
    interface Answers {
        /** What holds for a biome this sub-season, or null while Almanac has nothing to say about it. */
        @Nullable Place placeOf(ClientLevel level, Holder<Biome> biome);

        /** What falls at a position, by Almanac's own rule (0.1.3 on); empty before 0.1.3 and while Almanac is silent. */
        Optional<Biome.Precipitation> precipitationOf(ClientLevel level, Holder<Biome> biome, BlockPos pos);

        float worldRain(ClientLevel level);

        float worldThunder(ClientLevel level);
    }

    private final Answers almanac;
    /** Answers while Almanac is silent: the biome's own climate. */
    private final ClimateSource silent;
    private final Map<Biome, Place> places = new IdentityHashMap<>();

    SeasonalClimate(Answers almanac) {
        this(almanac, new VanillaClimate());
    }

    SeasonalClimate(Answers almanac, ClimateSource silent) {
        this.almanac = almanac;
        this.silent = silent;
    }

    @Override
    public String name() {
        return "Almanac";
    }

    @Override
    public Biome.Precipitation fallAt(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        Optional<Biome.Precipitation> falls = almanac.precipitationOf(level, biome, pos);
        if (falls.isPresent()) {
            return falls.get();
        }
        Place place = placeOf(level, biome);
        if (place == null) {
            return silent.fallAt(level, biome, pos);
        }
        // Almanac 0.1.2's rule: nothing where the event doesn't reach the climate, the copy's precipitation where it does.
        return place.reached() ? place.seasonal().value().getPrecipitationAt(pos, level.getSeaLevel()) : Biome.Precipitation.NONE;
    }

    @Override
    public boolean coldEnoughToSnow(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        Place place = placeOf(level, biome);
        return place == null ? silent.coldEnoughToSnow(level, biome, pos) : place.seasonal().value().coldEnoughToSnow(pos, level.getSeaLevel());
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
        if (place == null || !place.hasClimate()) {
            return silent.dryHeat(level, biome, pos);
        }
        return place.baseHot() && !place.seasonal().value().hasPrecipitation();
    }

    @Override
    public Holder<Biome> seasonalBiome(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        Place place = placeOf(level, biome);
        return place == null ? biome : place.seasonal();
    }

    /** Almanac sends the dimension's own levels too (0.1.2): each player's rain is only their share. */
    @Override
    public float worldRain(ClientLevel level) {
        return almanac.worldRain(level);
    }

    @Override
    public float worldThunder(ClientLevel level) {
        return almanac.worldThunder(level);
    }

    @Override
    public void forget() {
        places.clear();
    }

    /** A new sub-season, or a rain event reaching other climates: every biome is asked again. */
    void seasonChanged() {
        places.clear();
    }

    private @Nullable Place placeOf(ClientLevel level, Holder<Biome> biome) {
        Place place = places.get(biome.value());
        if (place == null) {
            place = almanac.placeOf(level, biome);
            if (place != null) {
                places.put(biome.value(), place);
            }
        }
        return place;
    }
}
