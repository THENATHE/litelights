package com.thenathe.litelights.render.feature.climate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Where {@link LocalClimate} gets its answers: the biome's own climate, a seasons mod's through the
 * game's hooked lookups, or Almanac's API. Client thread only.
 *
 * <p>Every method gets the biome the caller already looked up at {@code pos} (some read the noise
 * biome, some the blended one); a source may ask the level itself instead when that is what its
 * seasons mod hooks.
 */
interface ClimateSource {
    /** Shown in the log and in QA summaries. */
    String name();

    /** What falls at the position whenever it rains, as the game's own rain does there. */
    Biome.Precipitation fallAt(ClientLevel level, Holder<Biome> biome, BlockPos pos);

    /** Whether it is cold enough there for snow, whether or not anything falls. */
    boolean coldEnoughToSnow(ClientLevel level, Holder<Biome> biome, BlockPos pos);

    /**
     * Whether the season leaves the place dry and hot, so sandy land there can have a sandstorm and
     * desert heat. Says nothing about the land itself: sky-fog's biome table decides where is sandy.
     */
    boolean dryHeat(ClientLevel level, Holder<Biome> biome, BlockPos pos);

    /** The biome whose climate holds at the position now: a seasonal copy, or the biome itself. */
    Holder<Biome> seasonalBiome(ClientLevel level, Holder<Biome> biome, BlockPos pos);

    /**
     * How strong the rain is in the world as a whole, for weather that blows where nothing falls (a
     * sandstorm). The level's own rain level, unless a seasons mod gives each player a share of it.
     */
    default float worldRain(ClientLevel level) {
        return level.getRainLevel(1.0f);
    }

    /** The same for thunder (a haboob). */
    default float worldThunder(ClientLevel level) {
        return level.getThunderLevel(1.0f);
    }

    /** Once per client tick in a world, for sources that keep caches of their own. */
    default void tick(ClientLevel level) {
    }

    /** The world was left: anything kept about it is dropped. */
    default void forget() {
    }
}
