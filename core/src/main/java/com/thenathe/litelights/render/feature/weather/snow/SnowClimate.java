package com.thenathe.litelights.render.feature.weather.snow;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Where the season's snow can lie, for the visual snow: asked from chunk mesh threads, so every
 * answer must be safe from any thread. With Almanac its season's climate ({@link AlmanacSnow});
 * without it the biome's own climate ({@link #VANILLA}).
 */
interface SnowClimate {
    /** The biome's own climate: one area, and cold enough wherever vanilla's snow would fall. */
    SnowClimate VANILLA = new SnowClimate() {
        @Override
        public int area(ClientLevel level, Holder<Biome> biome) {
            return 0;
        }

        @Override
        public boolean coldEnough(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
            return biome.value().coldEnoughToSnow(pos, level.getSeaLevel());
        }

        @Override
        public SnowShares shares(ClientLevel level) {
            return SnowShares.NONE;
        }
    };

    /** Which area's shares a biome's snow follows (an index into {@link SnowShares}), or -1 for none. */
    int area(ClientLevel level, Holder<Biome> biome);

    /** Whether the season makes a spot cold enough for its snow to lie. */
    boolean coldEnough(ClientLevel level, Holder<Biome> biome, BlockPos pos);

    /** The season's shares now, or {@link SnowShares#NONE} where the source has none (then they are measured). */
    SnowShares shares(ClientLevel level);
}
