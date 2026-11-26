package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
 * Far snow by place (FarSnowPlaces) under Almanac's regional weather: each place's layer shares at
 * one of Almanac's snow nodes, asked in the background at most once a second and only within the
 * far land's reach; a place Almanac doesn't know keeps what it last knew, else the average of the
 * places known; and the far pass's reading of them (far_snow.glsl, lite_far_snow_shares, mirrored
 * here): each place's own from the camera out, eased between places as Almanac weighs a chunk's snow
 * from its nodes. Without regional weather nothing is asked and far land takes the snow here everywhere.
 */
class FarSnowPlacesTest {
    private static final int FLOOR = -64;
    private static final Executor AT_ONCE = Runnable::run;
    private static final int TEMPERATE = 1;
    private static final int COLD = 2;

    /**
     * Almanac under regional weather: a storm has left two layers' worth of snow on temperate land
     * east of x 2,000; from x {@link #unknownFrom} to x -2,000 Almanac doesn't know yet (none by
     * default); where the player stands, no snow but cold land's.
     */
    private static final class RegionalAlmanac implements FarSnowSource {
        boolean regional = true;
        int unknownFrom = -2000;
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
            if (x >= unknownFrom && x < -2000) {
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

    /** How many places a fill asks about within {@code reach} places of the camera's. */
    private static int placesWithin(int reach) {
        int count = 0;
        for (int z = 0; z < FarSnowPlaces.CELLS; z++) {
            for (int x = 0; x < FarSnowPlaces.CELLS; x++) {
                int outX = x - FarSnowPlaces.CELLS / 2;
                int outZ = z - FarSnowPlaces.CELLS / 2;
                count += outX * outX + outZ * outZ <= reach * reach ? 1 : 0;
            }
        }
        return count;
    }

    /**
     * lite_far_snow_shares for a column: the snow here without the places, else the places' texels
     * blended between their middles as the linear sampler does.
     */
    private static float[] shaderShares(ByteBuffer texels, float[] here, int climate, int x, int z, int originX, int originZ, float placesFrom) {
        if (placesFrom < 0.0f) {
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
            shares[layer] = top + (bottom - top) * fz;
        }
        return shares;
    }

    @Test
    void eachPlaceIsASnowNodeWithItsOwnSnow() {
        RegionalAlmanac almanac = new RegionalAlmanac();
        Recorded recorded = new Recorded();
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> 0L);
        snow.update(null, FLOOR, 100, 100, 0, 0.0f);
        snow.update(null, FLOOR, 100, 100, 1, 0.0f);
        assertEquals(1, recorded.places.size());
        assertEquals(placesWithin(FarSnowPlaces.MOST_REACH), almanac.asked, "one question a place within reach");
        assertEquals(FarSnowPlaces.BYTES, recorded.places.getFirst().limit());

        ByteBuffer texels = recorded.places.getFirst();
        int origin = FarSnowPlaces.originFor(100);
        assertEquals(-32 * 256 - 128, origin, "the camera's node the middle place, each place's middle a node");
        assertEquals(0, origin + 32 * FarSnowPlaces.CELL_SIZE + FarSnowPlaces.CELL_SIZE / 2);
        // East of x 2,000 the storm's snow on temperate land, exactly to its steps.
        float[] east = sharesOf(texels, TEMPERATE, placeOf(4096, origin), placeOf(0, origin));
        assertEquals(1.0f, east[0]);
        assertEquals(SnowPoints.quantize(0.6), east[1]);
        assertEquals(0.0f, east[2]);
        // Here, none on temperate land, and cold land's snow where Almanac says so.
        assertEquals(0.0f, sharesOf(texels, TEMPERATE, placeOf(100, origin), placeOf(100, origin))[0]);
        assertEquals(0.0f, sharesOf(texels, COLD, placeOf(4096, origin), placeOf(0, origin))[0], "a known place without cold snow");
        assertTrue(snow.describe().contains("byPlace=true"), snow.describe());
    }

    @Test
    void onlyThePlacesWithinTheFarLandsReachAreAsked() {
        RegionalAlmanac almanac = new RegionalAlmanac();
        Recorded recorded = new Recorded();
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> 0L);
        snow.setReach(4096.0f);
        snow.update(null, FLOOR, 0, 0, 0, 0.0f);
        snow.update(null, FLOOR, 0, 0, 1, 0.0f);
        assertEquals(17, FarSnowPlaces.reachFor(4096.0f));
        assertEquals(placesWithin(17), almanac.asked);
        // Almanac 1.1.0 and older work out 2,048 nodes on a client: the most ever asked stays within them.
        int most = placesWithin(FarSnowPlaces.MOST_REACH);
        assertTrue(most + 2 * Math.PI * FarSnowPlaces.MOST_REACH + 4 < 2048, "places and the nodes round their edge: " + most);
        assertEquals(FarSnowPlaces.MOST_REACH, FarSnowPlaces.reachFor(16384.0f));
    }

    @Test
    void anUnknownPlaceKeepsWhatItLastHadElseTheAverageOfThePlacesKnown() {
        RegionalAlmanac almanac = new RegionalAlmanac();
        Recorded recorded = new Recorded();
        long[] now = {0L};
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> now[0]);
        snow.setReach(4096.0f);
        // The first fill: Almanac doesn't know the land from x -3,000 to -2,000 yet.
        almanac.unknownFrom = -3000;
        snow.update(null, FLOOR, 0, 0, 0, 0.0f);
        snow.update(null, FLOOR, 0, 0, 1, 0.0f);
        ByteBuffer first = recorded.places.getFirst();
        int origin = FarSnowPlaces.originFor(0);
        float[] average = sharesOf(first, TEMPERATE, placeOf(-2560, origin), placeOf(0, origin));
        float[] beyond = sharesOf(first, TEMPERATE, placeOf(-6144, origin), placeOf(0, origin));
        assertTrue(average[0] > 0.0f && average[0] < 1.0f, "never known: the average of the places known, " + average[0]);
        assertArrayEquals(average, beyond, "out of reach: the same average");
        assertEquals(0.0f, sharesOf(first, COLD, placeOf(-2560, origin), placeOf(0, origin))[0], "not the snow here (cold 1.0)");

        // Known in the second fill, forgotten in the third (Almanac let it go and is working it out again).
        almanac.unknownFrom = -2000;
        now[0] += FarSnowPlaces.INTERVAL_NANOS;
        snow.update(null, FLOOR, 0, 0, 2, 0.0f);
        snow.update(null, FLOOR, 0, 0, 3, 0.0f);
        almanac.unknownFrom = -3000;
        now[0] += FarSnowPlaces.INTERVAL_NANOS;
        snow.update(null, FLOOR, 0, 0, 4, 0.0f);
        snow.update(null, FLOOR, 0, 0, 5, 0.0f);
        assertEquals(3, recorded.places.size());
        assertEquals(0.0f, sharesOf(recorded.places.get(2), TEMPERATE, placeOf(-2560, origin), placeOf(0, origin))[0],
            "what it had when last known: none");
        assertTrue(snow.describe().contains("placesRecalled=" + placesInBand(17, -3000) + ","), snow.describe());
    }

    /** The places within reach of the camera at 0, 0 whose middles lie from x {@code from} to x -2,000. */
    private static int placesInBand(int reach, int from) {
        int count = 0;
        int origin = FarSnowPlaces.originFor(0);
        for (int z = 0; z < FarSnowPlaces.CELLS; z++) {
            for (int x = 0; x < FarSnowPlaces.CELLS; x++) {
                int outX = x - FarSnowPlaces.CELLS / 2;
                int outZ = z - FarSnowPlaces.CELLS / 2;
                int middle = origin + x * FarSnowPlaces.CELL_SIZE + FarSnowPlaces.CELL_SIZE / 2;
                if (outX * outX + outZ * outZ <= reach * reach && middle >= from && middle < -2000) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void theFarPassTakesEachPlacesSnowFromTheCameraOut() {
        RegionalAlmanac almanac = new RegionalAlmanac();
        Recorded recorded = new Recorded();
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> 0L);
        // The player stands on bare temperate land 300 blocks west of the storm's snow.
        snow.update(null, FLOOR, 1700, 0, 0, 0.0f);
        snow.update(null, FLOOR, 1700, 0, 1, 0.0f);
        ByteBuffer texels = recorded.places.getFirst();
        int originX = FarSnowPlaces.originFor(1700);
        int originZ = FarSnowPlaces.originFor(0);
        float[] here = {0.0f, 0.0f, 0.0f, 0.0f};
        float from = snow.placesFrom();
        assertEquals(0.0f, from);

        // At a node with the storm's snow 400 blocks off, past a render distance of 12 chunks: its own
        // snow, not the snow here (1.1.4 eased from the snow here over the 512 blocks past the render distance).
        float[] node = shaderShares(texels, here, TEMPERATE, 2048, 0, originX, originZ, from);
        assertEquals(1.0f, node[0], 1.0e-6f);
        assertEquals(SnowPoints.quantize(0.6), node[1], 1.0e-6f);
        // Half way between a bare node and a snowy one, half of each, as Almanac weighs a chunk there.
        float[] between = shaderShares(texels, here, TEMPERATE, 1920, 0, originX, originZ, from);
        assertEquals(0.5f, between[0], 0.01f);
        // Further out, the same steps Almanac gave.
        float[] far = shaderShares(texels, here, TEMPERATE, 5120, 256, originX, originZ, from);
        assertEquals(1.0f, far[0], 1.0e-6f);
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
        assertEquals(3 * placesWithin(FarSnowPlaces.MOST_REACH), almanac.asked);
        assertEquals(3, almanac.askedHere, "the snow here once a fill, not per frame");
        assertTrue(snow.describe().contains("placesFills=3"), snow.describe());
    }

    @Test
    void withoutRegionalWeatherNothingIsAskedAndTheSnowHereIsUsedEverywhere() {
        RegionalAlmanac almanac = new RegionalAlmanac();
        almanac.regional = false;
        Recorded recorded = new Recorded();
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> 0L);
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
        assertEquals(0.0f, snow.placesFrom());
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
