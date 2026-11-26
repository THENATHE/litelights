package com.thenathe.litelights.render.feature.clouds;

import com.thenathe.litelights.clouds.field.CloudDrift;
import com.thenathe.litelights.clouds.field.CloudField;
import com.thenathe.litelights.clouds.field.CloudSky;
import com.thenathe.litelights.clouds.field.RegionalSky;
import com.thenathe.litelights.clouds.field.RegionalStorm;
import com.thenathe.litelights.clouds.field.VanillaWeatherSky;
import com.thenathe.litelights.clouds.volume.CloudMap;
import com.thenathe.litelights.clouds.volume.CloudNoiseVolume;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Builds the clouds on worker threads, one generation at a time: the sky for the build's tick, then
 * the cloud field written out as column maps (clouds.volume.CloudMap), a strip of rows per task so
 * the workers share them. The near map, of {@link #NEAR_TEXEL}-block columns round the camera, is
 * built every time; the far map, of wider columns out to the reach, only every
 * {@link #FAR_EVERY} builds (its clouds change no faster and are smaller on screen) or when the
 * camera has moved off its middle. Nothing waits on anything: the render thread only looks at the
 * result once it is done.
 */
final class CloudBuilder implements AutoCloseable {
    /** The near map's columns, in blocks: 4 km across. */
    static final int NEAR_TEXEL = 8;
    /** The far map's narrowest columns, in blocks; wider for a longer reach, in steps of this. */
    private static final int FAR_TEXEL_STEP = 8;
    /** The far map is sampled again every this many builds (and its change blended in over as many). */
    static final int FAR_EVERY = 4;
    /** ...or as soon as the camera is this share of its width off its middle. */
    private static final double FAR_RECENTRE_SHARE = 0.1;
    /** Each map is shared out in this many strips of rows. */
    private static final int STRIPS = 16;

    private final ExecutorService workers;
    private @Nullable CompletableFuture<CloudVolume.NoiseAtlases> noise;

    CloudBuilder() {
        int threads = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 4));
        workers = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "LiteLights clouds");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /**
     * What a build needs, all of it plain data taken on the render thread.
     *
     * @param tick     the tick (with its fraction) the clouds are built for: the game time, or with
     *                 Almanac's regional weather its weather tick
     * @param rain     the clouds' rain level (clouds.field.CloudWeather), for LiteLights' own sky
     * @param cameraX  the camera in world space
     * @param reach    how far from the camera clouds are drawn, in blocks
     * @param previous the last generation, whose far map is reused while it isn't due; null to build
     *                 everything afresh (the first build, or after a jump)
     * @param number   counts builds, for when the far map is due
     * @param storms   a weather mod's storms to draw (Almanac's regional weather), or null for
     *                 LiteLights' own sky
     * @param wind     the wind the clouds ride: LiteLights' own, or the weather mod's
     */
    record Request(long seed, float floor, double tick, float rain, float thunder, double cameraX, double cameraZ, int reach,
                   @Nullable Generation previous, long number, @Nullable List<RegionalStorm> storms, CloudDrift wind) {
    }

    /** Runs a job on the workers (summing a weather mod's wind ahead of the first build). */
    CompletableFuture<Void> run(Runnable job) {
        return CompletableFuture.runAsync(job, workers);
    }

    /** The noise atlases' bytes (clouds.volume.CloudNoiseVolume), made once on a worker. */
    CompletableFuture<CloudVolume.NoiseAtlases> noise() {
        if (noise == null) {
            noise = CompletableFuture.supplyAsync(() -> new CloudVolume.NoiseAtlases(CloudNoiseVolume.build(), CloudNoiseVolume.buildLumps()), workers);
        }
        return noise;
    }

    /**
     * The far map's column width for a reach: wide enough that the map still covers the reach
     * however far the camera goes off its middle before it is built again (40 blocks at the
     * default 8 km reach, 20 km across).
     */
    static int farTexel(int reach) {
        double halfWidthNeeded = reach / (0.5 - FAR_RECENTRE_SHARE);
        int texel = (int) Math.ceil(halfWidthNeeded / CloudMap.SIZE / FAR_TEXEL_STEP) * FAR_TEXEL_STEP;
        return Math.max(2 * NEAR_TEXEL, texel);
    }

    /** Starts a build; the future completes on a worker thread. */
    CompletableFuture<Generation> build(Request request) {
        long started = System.nanoTime();
        return CompletableFuture.supplyAsync(() -> new CloudField(sky(request)), workers).thenCompose(field -> {
            double cameraCloudX = request.cameraX - field.driftX(request.tick);
            double cameraCloudZ = request.cameraZ - field.driftZ(request.tick);
            CloudMap.Builder near = CloudMap.around(cameraCloudX, cameraCloudZ, NEAR_TEXEL);
            CompletableFuture<Integer> nearClouded = sample(near, field, request.tick);
            CloudMap.Builder far = farDue(request, cameraCloudX, cameraCloudZ) ? CloudMap.around(cameraCloudX, cameraCloudZ, farTexel(request.reach))
                : null;
            CompletableFuture<Integer> farDone = far != null ? sample(far, field, request.tick) : CompletableFuture.completedFuture(0);
            CompletableFuture<Double> nearMillis = nearClouded.thenApply(done -> (System.nanoTime() - started) / 1.0e6);
            return nearClouded.thenCombine(farDone, (clouded, ignored) -> {
                double buildMillis = (System.nanoTime() - started) / 1.0e6;
                CloudMap farMap = far != null ? far.build() : request.previous.far();
                return new Generation(request.tick, request.floor, near.build(), farMap, far != null, cameraCloudX, cameraCloudZ, clouded,
                    buildMillis, nearMillis.join(), far != null ? buildMillis : 0.0);
            });
        });
    }

    /** Whether the far map must be sampled in this build. */
    private static boolean farDue(Request request, double cameraCloudX, double cameraCloudZ) {
        Generation previous = request.previous;
        if (previous == null || request.number % FAR_EVERY == 0 || previous.far().texelBlocks() != farTexel(request.reach)) {
            return true;
        }
        CloudMap far = previous.far();
        double limit = far.spanBlocks() * FAR_RECENTRE_SHARE;
        double dx = cameraCloudX - far.centreX();
        double dz = cameraCloudZ - far.centreZ();
        return dx * dx + dz * dz > limit * limit;
    }

    /** Samples every row of a map, a strip per task; completes with how many columns held cloud. */
    private CompletableFuture<Integer> sample(CloudMap.Builder map, CloudField field, double tick) {
        List<CompletableFuture<Integer>> strips = new ArrayList<>(STRIPS);
        int rows = CloudMap.SIZE / STRIPS;
        for (int strip = 0; strip < STRIPS; strip++) {
            int first = strip * rows;
            strips.add(CompletableFuture.supplyAsync(() -> map.sampleRows(field, tick, first, first + rows), workers));
        }
        return CompletableFuture.allOf(strips.toArray(CompletableFuture[]::new)).thenApply(done -> {
            int clouded = 0;
            for (CompletableFuture<Integer> strip : strips) {
                clouded += strip.join();
            }
            return clouded;
        });
    }

    private static CloudSky sky(Request request) {
        // Formations are kept a little beyond the reach, so a cloud just outside it is already there to drift in.
        double reach = request.reach + 512.0;
        if (request.storms != null) {
            return RegionalSky.sky(request.seed, request.floor, request.tick, request.storms, request.wind, request.cameraX,
                request.cameraZ, reach);
        }
        CloudSky own = VanillaWeatherSky.sky(request.seed, request.floor, request.tick, request.rain, request.thunder,
            request.cameraX, request.cameraZ, reach);
        // Under a weather mod's wind (a previewed weather over Almanac's regional weather), its clouds ride that wind too.
        return own.wind().equals(request.wind) ? own : new CloudSky(own.seed(), own.baseHeight(), request.wind, own.formations());
    }

    @Override
    public void close() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
