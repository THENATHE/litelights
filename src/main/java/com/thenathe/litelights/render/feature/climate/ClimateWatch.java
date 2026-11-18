package com.thenathe.litelights.render.feature.climate;

import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Notices the season changing without being told: once a tick it reads the climate under the player
 * (at sea level, so walking up a slope past the snow line doesn't count), and when the same biome
 * answers differently from the tick before, or arrives as another sub-season's copy, the climate has
 * changed. That covers seasons mods without an event and Almanac on the server only, where the
 * server sends the next sub-season's copies but nothing that LiteLights' chunk scans would notice.
 * Without a seasons mod the answers never change, so it never fires.
 */
final class ClimateWatch {
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private @Nullable ClientLevel lastLevel;
    private @Nullable ResourceKey<Biome> lastId;
    private int lastSubSeason = -1;
    private Biome.@Nullable Precipitation lastFall;
    private boolean lastCold;

    /** Reads the column at (x, z); true if its climate changed since the last reading in the same place. */
    boolean changed(ClientLevel level, ClimateSource source, int x, int z) {
        cursor.set(x, level.getSeaLevel(), z);
        Holder<Biome> biome = level.getBiome(cursor);
        ResourceKey<Biome> id = SeasonalCopies.originalKey(biome);
        int subSeason = SeasonalCopies.subSeasonOf(biome);
        Biome.Precipitation fall = source.fallAt(level, biome, cursor);
        boolean cold = source.coldEnoughToSnow(level, biome, cursor);

        boolean samePlace = level == lastLevel && id != null && Objects.equals(id, lastId);
        boolean changed = samePlace && (subSeason != lastSubSeason || fall != lastFall || cold != lastCold);
        lastLevel = level;
        lastId = id;
        lastSubSeason = subSeason;
        lastFall = fall;
        lastCold = cold;
        return changed;
    }

    void forget() {
        lastLevel = null;
        lastId = null;
        lastFall = null;
    }
}
