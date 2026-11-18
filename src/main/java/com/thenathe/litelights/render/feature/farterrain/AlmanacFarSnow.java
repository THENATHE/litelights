package com.thenathe.litelights.render.feature.farterrain;

import com.thenathe.almanac.api.AlmanacApi;
import com.thenathe.almanac.api.AlmanacEvents;
import com.thenathe.almanac.api.Climate;
import com.thenathe.litelights.render.feature.climate.AlmanacVersion;
import java.util.OptionalInt;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Almanac's far-terrain lookups (0.1.2 on; Almanac's docs/FAR-SNOW.md). With render.feature.climate's
 * AlmanacClimate, the only classes that name Almanac; loaded only when Almanac is installed.
 * fabric.mod.json refuses an Almanac older than 0.1.2, so the lookups are always there; FarSnow
 * still catches a LinkageError from {@link #listen}, the first call that needs them, and keeps the
 * far mods' captured snow. 0.1.3's change tick is only asked of 0.1.3 or later (AlmanacVersion).
 */
final class AlmanacFarSnow implements FarSnowSource {
    private static final Climate[] CLIMATES = {Climate.HOT, Climate.TEMPERATE, Climate.COLD};

    /** Almanac 0.1.3 or later on the client, so the change tick can be asked for. */
    private final boolean newer = AlmanacVersion.atLeast(AlmanacVersion.CHANGE_TICK);

    private AlmanacFarSnow() {
    }

    static FarSnowSource create() {
        return new AlmanacFarSnow();
    }

    @Override
    public void listen(Changes listener) {
        AlmanacEvents.FAR_CLIMATE_CHANGED.register((level, change) -> {
            if (!(level instanceof ClientLevel client)) {
                return;
            }
            Area area = change.isEverywhere() ? Area.EVERYWHERE : new Area(change.minX(), change.minZ(), change.maxX(), change.maxZ());
            // Only a change of what the season shows comes alone, everywhere, for the snow lines
            // (FAR-SNOW.md, "Knowing when to fill again"); the map switched on or off brings all three.
            boolean season = change.isEverywhere() && change.snowLines() && !change.colours() && !change.climate();
            listener.changed(client, area, change.snowLines(), change.colours(), season ? Kind.SEASON : Kind.MAP);
        });
    }

    @Override
    public void fillLines(ClientLevel level, int originX, int originZ, int spacing, int width, int height, int[] lines) {
        AlmanacApi.fillFarSnowLines(level, originX, originZ, spacing, width, height, lines);
    }

    @Override
    public int grassColour(ClientLevel level, int x, int z) {
        OptionalInt colour = AlmanacApi.seasonalGrassColour(level, x, z);
        return colour.isPresent() ? colour.getAsInt() & 0xFFFFFF : -1;
    }

    @Override
    public int foliageColour(ClientLevel level, int x, int z) {
        OptionalInt colour = AlmanacApi.seasonalFoliageColour(level, x, z);
        return colour.isPresent() ? colour.getAsInt() & 0xFFFFFF : -1;
    }

    @Override
    public boolean hot(ClientLevel level, int x, int z) {
        return AlmanacApi.farClimate(level, x, z).map(climate -> climate.base() == Climate.HOT).orElse(false);
    }

    @Override
    public long thawTicks(ClientLevel level) {
        return AlmanacApi.thawTicks(level).orElse(-1L);
    }

    @Override
    public long changedTick(ClientLevel level) {
        return newer ? AlmanacApi.farSnowChangedTick(level).orElse(-1L) : -1L;
    }

    @Override
    public void snowDepths(ClientLevel level, double[] depths) {
        for (int i = 0; i < CLIMATES.length; i++) {
            depths[i] = AlmanacApi.snowDepth(level, CLIMATES[i]).orElse(-1.0);
        }
    }

    @Override
    public int subSeason(ClientLevel level) {
        return AlmanacApi.subSeason(level).map(Enum::ordinal).orElse(-1);
    }
}
