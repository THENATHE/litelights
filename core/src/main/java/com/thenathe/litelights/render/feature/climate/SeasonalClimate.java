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
 * <p>Per biome it keeps what Almanac says holds there this sub-season (its {@code SeasonClimate}):
 * the season's temperature, how cold it is, whether anything can fall, and whether the biome is hot
 * all year. {@link #seasonChanged} drops all of it.
 *
 * <p>Where Almanac has nothing to say (outside the overworld, before the server's first season
 * sync, on a server without Almanac, or for a biome without seasons) the biome answers as it does
 * without a seasons mod
 * ({@link VanillaClimate}), for the biome the caller asked about, loaded or not. Such a silent answer
 * is never kept, so the first sync is seen whatever its event says.
 */
final class SeasonalClimate implements ClimateSource {
    /** What holds for one biome this sub-season: Almanac's {@code SeasonClimate}, as LiteLights reads it. */
    interface Place {
        /** The biome's temperature this sub-season: what {@code getBaseTemperature} would say with the season's climate. */
        float temperature();

        /** Whether the season makes a position cold enough for snow, whether or not anything falls. */
        boolean coldEnoughToSnow(BlockPos pos, int seaLevel);

        /** Whether rain or snow can fall here this sub-season when a rain event reaches the climate. */
        boolean canFall();

        /** Whether the biome's all-year climate is Almanac's HOT (the deserts and badlands). */
        boolean baseHot();
    }

    /** Almanac's answers, as asked through its API. */
    interface Answers {
        /** What holds for a biome this sub-season, or null while Almanac has nothing to say about it. */
        @Nullable Place placeOf(ClientLevel level, Holder<Biome> biome);

        /** What falls at a position, by Almanac's own rule; empty while Almanac is silent. */
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
        return almanac.precipitationOf(level, biome, pos).orElseGet(() -> silent.fallAt(level, biome, pos));
    }

    @Override
    public boolean coldEnoughToSnow(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        Place place = placeOf(level, biome);
        return place == null ? silent.coldEnoughToSnow(level, biome, pos) : place.coldEnoughToSnow(pos, level.getSeaLevel());
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
        if (place == null) {
            return silent.dryHeat(level, biome, pos);
        }
        return place.baseHot() && !place.canFall();
    }

    @Override
    public float temperature(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        Place place = placeOf(level, biome);
        return place == null ? silent.temperature(level, biome, pos) : place.temperature();
    }

    /** Almanac knows the dimension's own levels too: each player's rain is only their share. */
    @Override
    public float worldRain(ClientLevel level) {
        return almanac.worldRain(level);
    }

    @Override
    public float worldThunder(ClientLevel level) {
        return almanac.worldThunder(level);
    }

    /** Almanac's rain events reach a share of each climate, set per sub-season. */
    @Override
    public boolean sharesRainOut() {
        return true;
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
