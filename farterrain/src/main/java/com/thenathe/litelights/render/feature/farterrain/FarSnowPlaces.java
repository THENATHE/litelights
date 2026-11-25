package com.thenathe.litelights.render.feature.farterrain;

import com.thenathe.litelights.api.SnowPoints;
import java.nio.ByteBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/**
 * The season's snow by place, for far land under Almanac's regional weather, where the snow lies
 * deeper under the storms that brought it and differs from the snow where the player stands. A
 * grid of {@link #CELLS} by {@link #CELLS} places {@link #CELL_SIZE} blocks apart round the camera
 * (32 km across, as far as the outer ring reaches), each with every climate's layer shares at its
 * middle (Almanac's {@code snowLayersAt}). Filled on a background thread at most once a second
 * ({@link #INTERVAL_NANOS}), never per frame; a place Almanac doesn't know takes the shares where
 * the player stands.
 *
 * <p>The shares go into the rows of the far snow colours texture under the colours
 * ({@link #FIRST_ROW} on), so the snow pipelines need no other sampler: a texel per place and
 * climate, x the climate's block of {@link #CELLS} texels and the place's x in it, y the place's z;
 * red to alpha layers 1 to 4, each as its share's step (0 to SnowPoints.STEPS), so the far pass
 * reads the same steps back exactly at a place's middle and eases between places (far_snow.glsl,
 * {@code lite_far_snow_shares}). Without regional weather nothing here is filled and far land takes
 * the shares where the player stands everywhere, as before.
 */
final class FarSnowPlaces {
    static final int CELLS = 64;
    static final int CELL_SIZE = 512;
    /** The places' rows start under the grass and foliage rows. */
    static final int FIRST_ROW = FarSnow.COLOUR_SIZE * 2;
    static final int WIDTH = CELLS * 3;
    static final int BYTES = WIDTH * CELLS * 4;
    static final long INTERVAL_NANOS = 1_000_000_000L;

    private FarSnowPlaces() {
    }

    /** The first place's corner for a camera: the camera's place in the middle of the grid. */
    static int originFor(double camera) {
        return Math.floorDiv((int) Math.floor(camera), CELL_SIZE) * CELL_SIZE - CELLS / 2 * CELL_SIZE;
    }

    /** A share as the step it is held to, 0 to SnowPoints.STEPS: one byte of a texel. */
    static int step(float share) {
        return Math.clamp(Math.round(share * SnowPoints.STEPS), 0, SnowPoints.STEPS);
    }

    /** One fill of every place, run on a background thread. */
    static final class Job {
        final @Nullable ClientLevel level;
        final FarSnowSource source;
        final int originX;
        final int originZ;
        /** The shares where the player stands as the fill starts, for places Almanac doesn't know. */
        final float[][] here;
        final ByteBuffer staging;
        // The result.
        int known;
        long nanos;

        Job(@Nullable ClientLevel level, FarSnowSource source, int originX, int originZ, float[][] here, ByteBuffer staging) {
            this.level = level;
            this.source = source;
            this.originX = originX;
            this.originZ = originZ;
            this.here = here;
            this.staging = staging;
        }

        Job run() {
            long started = System.nanoTime();
            float[][] shares = new float[3][FarSnow.SHARED_LAYERS];
            for (int z = 0; z < CELLS; z++) {
                for (int x = 0; x < CELLS; x++) {
                    int middleX = originX + x * CELL_SIZE + CELL_SIZE / 2;
                    int middleZ = originZ + z * CELL_SIZE + CELL_SIZE / 2;
                    boolean placeKnown = source.snowSharesAt(level, middleX, middleZ, shares);
                    if (placeKnown) {
                        known++;
                    }
                    for (int climate = 0; climate < 3; climate++) {
                        float[] layers = placeKnown ? shares[climate] : here[climate];
                        int texel = step(layers[0]) | step(layers[1]) << 8 | step(layers[2]) << 16 | step(layers[3]) << 24;
                        staging.putInt((z * WIDTH + climate * CELLS + x) * 4, texel);
                    }
                }
            }
            staging.limit(BYTES);
            nanos = System.nanoTime() - started;
            return this;
        }
    }
}
