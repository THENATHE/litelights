package com.thenathe.litelights.render.feature.farterrain;

import com.thenathe.almanac.api.AlmanacApi;
import com.thenathe.almanac.api.AlmanacEvents;
import com.thenathe.almanac.api.Climate;
import com.thenathe.almanac.api.SnowLayers;
import com.thenathe.litelights.api.SnowPoints;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Almanac's far-terrain lookups (Almanac's docs/FAR-SNOW.md). With core's
 * render.feature.climate.AlmanacClimate, the only classes that name Almanac; loaded only when
 * Almanac 1.0.0 or later is installed (FarSnow.create checks), so every lookup used here is there.
 */
final class AlmanacFarSnow implements FarSnowSource {
    private static final Climate[] CLIMATES = {Climate.HOT, Climate.TEMPERATE, Climate.COLD};

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
            // (INTEGRATION.md, "Telling a season change from map data"); the map switched on or off brings all three.
            boolean season = change.isEverywhere() && change.snowLines() && !change.colours() && !change.climate();
            // The bare lines are today's: they move with the sub-season even when what far terrain shows doesn't.
            boolean lines = change.snowLines() || change.climate();
            listener.changed(client, area, lines, change.colours(), season ? Kind.SEASON : Kind.MAP);
        });
    }

    @Override
    public void fillLines(ClientLevel level, int originX, int originZ, int spacing, int width, int height, int[] lines) {
        AlmanacApi.fillFarSnowLines(level, originX, originZ, spacing, width, height, lines);
    }

    @Override
    public void fillBareLines(ClientLevel level, int originX, int originZ, int spacing, int width, int height, int[] lines) {
        AlmanacApi.fillFarBareSnowLines(level, originX, originZ, spacing, width, height, lines);
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
    public void fillAreas(ClientLevel level, int originX, int originZ, int spacing, int width, int height, byte[] areas) {
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                areas[z * width + x] = AlmanacApi.farClimate(level, originX + x * spacing, originZ + z * spacing)
                    .map(climate -> (byte) (areaOf(climate.base()) + 1)).orElse((byte) 0);
            }
        }
    }

    @Override
    public void snowShares(ClientLevel level, float[][] shares) {
        for (int area = 0; area < CLIMATES.length && area < shares.length; area++) {
            float[] layers = shares[area];
            Optional<SnowLayers> snow = AlmanacApi.snowLayers(level, CLIMATES[area]);
            for (int layer = 0; layer < layers.length; layer++) {
                layers[layer] = snow.isPresent() ? SnowPoints.quantize(snow.get().share(layer + 1)) : 0.0f;
            }
        }
    }

    @Override
    public boolean snowByPlace(ClientLevel level) {
        return AlmanacApi.regionalWeather(level);
    }

    @Override
    public boolean snowSharesAt(ClientLevel level, int x, int z, float[][] shares) {
        for (int area = 0; area < CLIMATES.length && area < shares.length; area++) {
            Optional<SnowLayers> snow = AlmanacApi.snowLayersAt(level, x + 0.5, z + 0.5, CLIMATES[area]);
            if (snow.isEmpty()) {
                return false;
            }
            float[] layers = shares[area];
            for (int layer = 0; layer < layers.length; layer++) {
                layers[layer] = SnowPoints.quantize(snow.get().share(layer + 1));
            }
        }
        return true;
    }

    private static int areaOf(Climate climate) {
        for (int area = 0; area < CLIMATES.length; area++) {
            if (CLIMATES[area] == climate) {
                return area;
            }
        }
        return 0;
    }

    @Override
    public void snowDepths(ClientLevel level, double[] depths) {
        for (int i = 0; i < CLIMATES.length; i++) {
            depths[i] = AlmanacApi.snowDepth(level, CLIMATES[i]).orElse(-1.0);
        }
    }
}
