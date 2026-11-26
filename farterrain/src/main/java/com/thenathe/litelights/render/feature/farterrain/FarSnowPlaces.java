package com.thenathe.litelights.render.feature.farterrain;

import com.thenathe.litelights.api.SnowPoints;
import java.nio.ByteBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/**
 * The season's snow by place, for far land under Almanac's regional weather, where the snow lies
 * deeper under the storms that brought it and differs from the snow where the player stands. A
 * grid of {@link #CELLS} by {@link #CELLS} places {@link #CELL_SIZE} blocks apart round the camera,
 * each at one of Almanac's snow nodes (every 256 blocks), with every climate's layer shares there
 * (Almanac's {@code snowLayersAt}). Almanac weighs a loaded chunk's snow from the four nodes round
 * it, and the colours' linear sampler eases between places the same way, so far land shows the
 * snow the near land that replaces it will have. Filled on a background thread at most once a
 * second ({@link #INTERVAL_NANOS}), never per frame.
 *
 * <p>Only the places within the far land's reach are asked about, and at most {@link #MOST_REACH}
 * places out: Almanac's client works out at most 2,048 nodes, and up to Almanac 1.1.0 it chose
 * which by its map's order rather than by nearness, so asking about the whole grid (as LiteLights
 * 1.1.0 to 1.1.4 did, 16,384 nodes) left most of the land round the player unknown. A place
 * Almanac doesn't know yet (the first ask starts the work) keeps what it was last known to have;
 * one never known, or out of reach, takes the average of the places known this fill, and only with
 * none known the snow where the player stands ({@link Remembered}).
 *
 * <p>The shares go into the rows of the far snow colours texture under the colours
 * ({@link #FIRST_ROW} on), so the snow pipelines need no other sampler: a texel per place and
 * climate, x the climate's block of {@link #CELLS} texels and the place's x in it, y the place's z;
 * red to alpha layers 1 to 4, each as its share's step (0 to SnowPoints.STEPS), so the far pass
 * reads the same steps back exactly at a place and eases between places (far_snow.glsl,
 * {@code lite_far_snow_shares}). Without regional weather nothing here is filled and far land takes
 * the shares where the player stands everywhere, as before.
 */
final class FarSnowPlaces {
    static final int CELLS = 64;
    /** Almanac's snow nodes' spacing: each place is one node. */
    static final int CELL_SIZE = 256;
    /** The places' rows start under the grass and foliage rows. */
    static final int FIRST_ROW = FarSnow.COLOUR_SIZE * 2;
    static final int WIDTH = CELLS * 3;
    static final int BYTES = WIDTH * CELLS * 4;
    static final long INTERVAL_NANOS = 1_000_000_000L;
    /**
     * The most places out from the camera's that are asked about: a disc of about 1,660 places,
     * which with the nodes round its edge stays under the 2,048 nodes Almanac's client works out.
     */
    static final int MOST_REACH = 23;
    private static final int LAYERS = FarSnow.SHARED_LAYERS;
    private static final int PER_PLACE = 3 * LAYERS;

    private FarSnowPlaces() {
    }

    /** The first place's corner for a camera: the snow node nearest below the camera is the middle place, 32. */
    static int originFor(double camera) {
        return Math.floorDiv((int) Math.floor(camera), CELL_SIZE) * CELL_SIZE - CELLS / 2 * CELL_SIZE - CELL_SIZE / 2;
    }

    /** How many places out are asked about for far land reaching {@code blocks} from the camera. */
    static int reachFor(float blocks) {
        return Math.clamp((int) Math.ceil(blocks / CELL_SIZE) + 1, 1, MOST_REACH);
    }

    /** A share as the step it is held to, 0 to SnowPoints.STEPS: one byte of a texel. */
    static int step(float share) {
        return Math.clamp(Math.round(share * SnowPoints.STEPS), 0, SnowPoints.STEPS);
    }

    /** The places' shares as a fill last knew them from Almanac, kept for the next fill. */
    record Remembered(int originX, int originZ, float[] shares, boolean[] known) {
        /** Whether the place at block {@code x}, {@code z} (a place's middle) was known, and its shares into {@code into}. */
        boolean recall(int x, int z, float[][] into) {
            int placeX = Math.floorDiv(x - originX, CELL_SIZE);
            int placeZ = Math.floorDiv(z - originZ, CELL_SIZE);
            if (placeX < 0 || placeZ < 0 || placeX >= CELLS || placeZ >= CELLS || !known[placeZ * CELLS + placeX]) {
                return false;
            }
            int at = (placeZ * CELLS + placeX) * PER_PLACE;
            for (int climate = 0; climate < 3; climate++) {
                System.arraycopy(shares, at + climate * LAYERS, into[climate], 0, LAYERS);
            }
            return true;
        }
    }

    /** One fill of every place, run on a background thread. */
    static final class Job {
        final @Nullable ClientLevel level;
        final FarSnowSource source;
        final int originX;
        final int originZ;
        final int reach;
        /** The shares where the player stands as the fill starts, for when no place is known at all. */
        final float[][] here;
        final @Nullable Remembered before;
        final ByteBuffer staging;
        // The result.
        int asked;
        int known;
        int recalled;
        Remembered remembered;
        long nanos;

        Job(@Nullable ClientLevel level, FarSnowSource source, int originX, int originZ, int reach, float[][] here,
            @Nullable Remembered before, ByteBuffer staging) {
            this.level = level;
            this.source = source;
            this.originX = originX;
            this.originZ = originZ;
            this.reach = reach;
            this.here = here;
            this.before = before;
            this.staging = staging;
        }

        Job run() {
            long started = System.nanoTime();
            float[] shares = new float[CELLS * CELLS * PER_PLACE];
            boolean[] keep = new boolean[CELLS * CELLS];
            float[][] place = new float[3][LAYERS];
            double[] sums = new double[PER_PLACE];
            for (int z = 0; z < CELLS; z++) {
                for (int x = 0; x < CELLS; x++) {
                    int outX = x - CELLS / 2;
                    int outZ = z - CELLS / 2;
                    if (outX * outX + outZ * outZ > reach * reach) {
                        continue;
                    }
                    asked++;
                    int middleX = originX + x * CELL_SIZE + CELL_SIZE / 2;
                    int middleZ = originZ + z * CELL_SIZE + CELL_SIZE / 2;
                    int index = z * CELLS + x;
                    if (source.snowSharesAt(level, middleX, middleZ, place)) {
                        keep[index] = true;
                        known++;
                        store(shares, index, place);
                        for (int value = 0; value < PER_PLACE; value++) {
                            sums[value] += place[value / LAYERS][value % LAYERS];
                        }
                    } else if (before != null && before.recall(middleX, middleZ, place)) {
                        keep[index] = true;
                        recalled++;
                        store(shares, index, place);
                    }
                }
            }
            float[] fallback = new float[PER_PLACE];
            for (int value = 0; value < PER_PLACE; value++) {
                float average = known == 0 ? here[value / LAYERS][value % LAYERS] : (float) (sums[value] / known);
                fallback[value] = SnowPoints.quantize(average);
            }
            for (int z = 0; z < CELLS; z++) {
                for (int x = 0; x < CELLS; x++) {
                    int index = z * CELLS + x;
                    for (int climate = 0; climate < 3; climate++) {
                        int texel = keep[index] ? texel(shares, index * PER_PLACE + climate * LAYERS) : texel(fallback, climate * LAYERS);
                        staging.putInt((z * WIDTH + climate * CELLS + x) * 4, texel);
                    }
                }
            }
            staging.limit(BYTES);
            remembered = new Remembered(originX, originZ, shares, keep);
            nanos = System.nanoTime() - started;
            return this;
        }

        /** Four layers' shares from {@code at} as a texel: red to alpha layers 1 to 4, each its step. */
        private static int texel(float[] shares, int at) {
            return step(shares[at]) | step(shares[at + 1]) << 8 | step(shares[at + 2]) << 16 | step(shares[at + 3]) << 24;
        }

        private static void store(float[] shares, int index, float[][] place) {
            for (int climate = 0; climate < 3; climate++) {
                System.arraycopy(place[climate], 0, shares, index * PER_PLACE + climate * LAYERS, LAYERS);
            }
        }
    }
}
