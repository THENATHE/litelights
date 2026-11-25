package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.api.SnowPoints;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.client.multiplayer.ClientLevel;
import org.junit.jupiter.api.Test;

/**
 * Far snow by place (FarSnowPlaces) under Almanac's regional weather: each place's layer shares,
 * asked in the background at most once a second, never per frame; a place Almanac doesn't know
 * takes the snow where the player stands; and the far pass's reading of them (far_snow.glsl,
 * lite_far_snow_shares, mirrored here): the snow here near the camera, each place's own past the
 * near land. Without regional weather nothing is asked and far land takes the snow here everywhere.
 */
class FarSnowPlacesTest {
    private static final int FLOOR = -64;
    private static final Executor AT_ONCE = Runnable::run;
    private static final int TEMPERATE = 1;
    private static final int COLD = 2;

    /**
     * Almanac under regional weather: a storm has left two layers' worth of snow on temperate land
     * east of x 2,000; west of x -8,000 isn't known yet; where the player stands, no snow but cold
     * land's.
     */
    private static final class RegionalAlmanac implements FarSnowSource {
        boolean regional = true;
        int asked;
        int askedHere;

        @Override
        public void listen(Changes listener) {
        }

        @Override
        public void fillLines(ClientLevel level, int originX, int originZ, int spacing, int width, int height, int[] lines) {
            Arrays.fill(lines, UNKNOWN);
        }

        @Override
        public void fillBareLines(ClientLevel level, int originX, int originZ, int spacing, int width, int height, int[] lines) {
            Arrays.fill(lines, UNKNOWN);
        }

        @Override
        public int grassColour(ClientLevel level, int x, int z) {
            return -1;
        }

        @Override
        public int foliageColour(ClientLevel level, int x, int z) {
            return -1;
        }

        @Override
        public boolean hot(ClientLevel level, int x, int z) {
            return false;
        }

        @Override
        public void snowDepths(ClientLevel level, double[] depths) {
            Arrays.fill(depths, 0.0);
        }

        @Override
        public void snowShares(ClientLevel level, float[][] shares) {
            askedHere++;
            for (float[] layers : shares) {
                Arrays.fill(layers, 0.0f);
            }
            shares[COLD][0] = 1.0f;
            shares[COLD][1] = SnowPoints.quantize(0.3);
        }

        @Override
        public boolean snowByPlace(ClientLevel level) {
            return regional;
        }

        @Override
        public boolean snowSharesAt(ClientLevel level, int x, int z, float[][] shares) {
            asked++;
            if (x < -8000) {
                return false;
            }
            for (float[] layers : shares) {
                Arrays.fill(layers, 0.0f);
            }
            if (x >= 2000) {
                shares[TEMPERATE][0] = 1.0f;
                shares[TEMPERATE][1] = SnowPoints.quantize(0.6);
            }
            return true;
        }
    }

    private static final class Recorded implements FarSnow.Uploads {
        final List<ByteBuffer> places = new ArrayList<>();

        @Override
        public void lines(ByteBuffer texels, FarSnowGrid.Rect rect, int row) {
        }

        @Override
        public void colours(ByteBuffer texels) {
        }

        @Override
        public void places(ByteBuffer texels) {
            ByteBuffer copy = ByteBuffer.allocate(texels.limit()).order(ByteOrder.LITTLE_ENDIAN);
            copy.put(0, texels, 0, texels.limit());
            places.add(copy);
        }
    }

    /** A place's four layer shares for a climate, as the far pass reads them back at the place's middle. */
    private static float[] sharesOf(ByteBuffer texels, int climate, int placeX, int placeZ) {
        int texel = texels.getInt((placeZ * FarSnowPlaces.WIDTH + climate * FarSnowPlaces.CELLS + placeX) * 4);
        float[] shares = new float[4];
        for (int layer = 0; layer < 4; layer++) {
            shares[layer] = (texel >>> layer * 8 & 0xFF) / (float) SnowPoints.STEPS;
        }
        return shares;
    }

    private static int placeOf(int column, int origin) {
        return Math.floorDiv(column - origin, FarSnowPlaces.CELL_SIZE);
    }

    /**
     * lite_far_snow_shares for a column: the snow here up to the near land's reach, each place's
     * from {@link FarSnow#PLACES_EASE} blocks past it (between, a blend), with the places' texels
     * blended between their middles as the linear sampler does.
     */
    private static float[] shaderShares(ByteBuffer texels, float[] here, int climate, int x, int z, int originX, int originZ,
                                        int cameraX, int cameraZ, float placesFrom) {
        if (placesFrom < 0.0f) {
            return here;
        }
        float ease = Math.clamp(((float) Math.hypot(x - cameraX, z - cameraZ) - placesFrom) / FarSnow.PLACES_EASE, 0.0f, 1.0f);
        if (ease <= 0.0f) {
            return here;
        }
        float last = FarSnowPlaces.CELLS - 0.5f;
        float placeX = Math.clamp((x - originX + 0.5f) / FarSnowPlaces.CELL_SIZE, 0.5f, last) - 0.5f;
        float placeZ = Math.clamp((z - originZ + 0.5f) / FarSnowPlaces.CELL_SIZE, 0.5f, last) - 0.5f;
        int x0 = (int) Math.floor(placeX);
        int z0 = (int) Math.floor(placeZ);
        int x1 = Math.min(x0 + 1, FarSnowPlaces.CELLS - 1);
        int z1 = Math.min(z0 + 1, FarSnowPlaces.CELLS - 1);
        float fx = placeX - x0;
        float fz = placeZ - z0;
        float[] a = sharesOf(texels, climate, x0, z0);
        float[] b = sharesOf(texels, climate, x1, z0);
        float[] c = sharesOf(texels, climate, x0, z1);
        float[] d = sharesOf(texels, climate, x1, z1);
        float[] shares = new float[4];
        for (int layer = 0; layer < 4; layer++) {
            float top = a[layer] + (b[layer] - a[layer]) * fx;
            float bottom = c[layer] + (d[layer] - c[layer]) * fx;
            float there = top + (bottom - top) * fz;
            shares[layer] = here[layer] + (there - here[layer]) * ease;
        }
        return shares;
    }

    @Test
    void eachPlaceHasItsOwnSnowAndAnUnknownPlaceTheSnowHere() {
        RegionalAlmanac almanac = new RegionalAlmanac();
        Recorded recorded = new Recorded();
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> 0L);
        snow.setNearReach(192);
        snow.update(null, FLOOR, 100, 100, 0, 0.0f);
        snow.update(null, FLOOR, 100, 100, 1, 0.0f);
        assertEquals(1, recorded.places.size());
        assertEquals(FarSnowPlaces.CELLS * FarSnowPlaces.CELLS, almanac.asked, "one question a place");
        assertEquals(FarSnowPlaces.BYTES, recorded.places.getFirst().limit());

        ByteBuffer texels = recorded.places.getFirst();
        int origin = FarSnowPlaces.originFor(100);
        assertEquals(-32 * 512, origin, "the camera's place in the middle");
        // East of x 2,000 the storm's snow on temperate land, exactly to its steps.
        float[] east = sharesOf(texels, TEMPERATE, placeOf(4000, origin), placeOf(0, origin));
        assertEquals(1.0f, east[0]);
        assertEquals(SnowPoints.quantize(0.6), east[1]);
        assertEquals(0.0f, east[2]);
        // Here, none on temperate land.
        assertEquals(0.0f, sharesOf(texels, TEMPERATE, placeOf(100, origin), placeOf(100, origin))[0]);
        // West of x -8,000 Almanac doesn't know: the snow here, cold land's included.
        float[] west = sharesOf(texels, COLD, placeOf(-12000, origin), placeOf(0, origin));
        assertEquals(1.0f, west[0]);
        assertEquals(SnowPoints.quantize(0.3), west[1]);
        assertEquals(0.0f, sharesOf(texels, COLD, placeOf(4000, origin), placeOf(0, origin))[0], "a known place without cold snow");
        assertTrue(snow.describe().contains("byPlace=true"), snow.describe());
    }

    @Test
    void theFarPassTakesTheSnowHereNearAndEachPlacesFurtherOut() {
        RegionalAlmanac almanac = new RegionalAlmanac();
        Recorded recorded = new Recorded();
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> 0L);
        snow.setNearReach(192);
        snow.update(null, FLOOR, 1900, 0, 0, 0.0f);
        snow.update(null, FLOOR, 1900, 0, 1, 0.0f);
        ByteBuffer texels = recorded.places.getFirst();
        int originX = FarSnowPlaces.originFor(1900);
        int originZ = FarSnowPlaces.originFor(0);
        float[] here = {0.0f, 0.0f, 0.0f, 0.0f};
        float from = snow.placesFrom();
        assertEquals(192.0f, from);

        // At the near land's edge, east of the storm's line, still the snow here: near and far agree.
        float[] edge = shaderShares(texels, here, TEMPERATE, 2090, 0, originX, originZ, 1900, 0, from);
        assertEquals(0.0f, edge[0]);
        // Three kilometres east, the place's own snow, the same steps Almanac gave.
        float[] far = shaderShares(texels, here, TEMPERATE, 5120 + 256, 256, originX, originZ, 1900, 0, from);
        assertEquals(1.0f, far[0], 1.0e-6f);
        assertEquals(SnowPoints.quantize(0.6), far[1], 1.0e-6f);
        // Half way through the ease, half of it.
        float[] easing = shaderShares(texels, here, TEMPERATE, 1900 + 192 + 256, 0, originX, originZ, 1900, 0, from);
        assertTrue(easing[0] > 0.2f && easing[0] < 0.8f, "eased: " + easing[0]);
    }

    @Test
    void placesAreAskedAtMostOnceASecondAndFollowTheCamera() {
        RegionalAlmanac almanac = new RegionalAlmanac();
        Recorded recorded = new Recorded();
        long[] now = {0L};
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> now[0]);
        // Three seconds at 60 frames a second, flying east 20 blocks a frame.
        for (int frame = 0; frame < 180; frame++) {
            snow.update(null, FLOOR, frame * 20.0, 0, frame, 0.0f);
            now[0] += 16_666_667L;
        }
        assertEquals(3, recorded.places.size(), "a fill a second");
        assertEquals(3 * FarSnowPlaces.CELLS * FarSnowPlaces.CELLS, almanac.asked);
        assertEquals(3, almanac.askedHere, "the snow here once a fill, not per frame");
        assertTrue(snow.describe().contains("placesFills=3"), snow.describe());
    }

    @Test
    void withoutRegionalWeatherNothingIsAskedAndTheSnowHereIsUsedEverywhere() {
        RegionalAlmanac almanac = new RegionalAlmanac();
        almanac.regional = false;
        Recorded recorded = new Recorded();
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> 0L);
        snow.setNearReach(192);
        snow.update(null, FLOOR, 0, 0, 0, 0.0f);
        snow.update(null, FLOOR, 0, 0, 1, 0.0f);
        assertEquals(0, almanac.asked);
        assertEquals(0, recorded.places.size());
        assertEquals(-1.0f, snow.placesFrom());

        // Regional weather switched on: the places are filled and read; switched off again, no longer read.
        almanac.regional = true;
        snow.update(null, FLOOR, 0, 0, 2, 0.0f);
        snow.update(null, FLOOR, 0, 0, 3, 0.0f);
        assertEquals(1, recorded.places.size());
        assertEquals(192.0f, snow.placesFrom());
        almanac.regional = false;
        snow.update(null, FLOOR, 0, 0, 4, 0.0f);
        assertEquals(-1.0f, snow.placesFrom());
    }

    @Test
    void leavingTheLevelForgetsThePlaces() {
        RegionalAlmanac almanac = new RegionalAlmanac();
        Recorded recorded = new Recorded();
        List<Runnable> queued = new ArrayList<>();
        FarSnow snow = new FarSnow(almanac, queued::add, recorded, () -> 0L);
        snow.update(null, FLOOR, 0, 0, 0, 0.0f);
        snow.leaveLevel();
        queued.forEach(Runnable::run);
        snow.update(null, FLOOR, 0, 0, 1, 0.0f);
        assertEquals(0, recorded.places.size(), "a fill still running when the level was left is never taken in");
        assertEquals(-1.0f, snow.placesFrom());
    }
}
