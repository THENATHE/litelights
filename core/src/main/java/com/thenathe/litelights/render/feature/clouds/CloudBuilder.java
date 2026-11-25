package com.thenathe.litelights.render.feature.clouds;

import com.thenathe.litelights.clouds.field.CloudColumn;
import com.thenathe.litelights.clouds.field.CloudDrift;
import com.thenathe.litelights.clouds.field.CloudField;
import com.thenathe.litelights.clouds.field.CloudSky;
import com.thenathe.litelights.clouds.field.RegionalSky;
import com.thenathe.litelights.clouds.field.RegionalStorm;
import com.thenathe.litelights.clouds.field.VanillaWeatherSky;
import com.thenathe.litelights.clouds.mesh.CloudCells;
import com.thenathe.litelights.clouds.mesh.CloudMesher;
import com.thenathe.litelights.clouds.mesh.CloudRings;
import com.thenathe.litelights.clouds.mesh.CloudShadowMap;
import com.thenathe.litelights.clouds.mesh.FaceFormat;
import com.thenathe.litelights.clouds.mesh.FaceList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Builds the clouds on worker threads, one generation at a time: the sky for the build's tick,
 * then every chunk in reach sampled and meshed in parallel against the same chunk of the last
 * generation, then the faces joined nearest first into one list for one draw. Nothing waits on
 * anything: each step starts the next as it finishes, and the render thread only looks at the
 * result once it is done.
 *
 * <p>The reach is laid out in rings (clouds.mesh.CloudRings): 8-block cubes near the camera, then
 * cubes twice as wide in each ring out. A far ring is sampled again only every few builds (its
 * clouds change no faster, and are smaller on screen); in between its chunks are reused as they
 * were. A chunk coming into reach, or into another ring as the camera moves, fades in over the
 * change while what it replaces fades out on the other pixels, so nothing pops.
 */
final class CloudBuilder implements AutoCloseable {
    /** A chunk of cloud space is this many blocks across at the near cube size. */
    static final int CHUNK_BLOCKS = CloudCells.SIZE * DynamicClouds.CUBE_BLOCKS;

    private final ExecutorService workers;
    /** Two buffers for the joined faces: one being uploaded while the next build fills the other. */
    private final ByteBuffer[] joined = new ByteBuffer[2];
    private int nextJoined;
    /** Builds run one at a time, so one shadow map's sums serve them all. */
    private final CloudShadowMap shadowMap = new CloudShadowMap();

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
     * @param previous the last generation, whose cells the changes are measured against; null to
     *                 build with nothing changing (the first build, or after a jump)
     * @param number   counts builds, for which far chunks are due to be sampled again
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

    /** Starts a build; the future completes on a worker thread. */
    CompletableFuture<Generation> build(Request request) {
        ByteBuffer target = joined[nextJoined];
        int targetIndex = nextJoined;
        nextJoined = 1 - nextJoined;
        long started = System.nanoTime();
        return CompletableFuture.supplyAsync(() -> new CloudField(sky(request)), workers)
            .thenCompose(field -> {
                double cameraCloudX = request.cameraX - field.driftX(request.tick);
                double cameraCloudZ = request.cameraZ - field.driftZ(request.tick);
                List<CloudRings.Chunk> chunks = CloudRings.select(cameraCloudX, cameraCloudZ, request.reach);
                Set<Long> selected = new HashSet<>();
                for (CloudRings.Chunk chunk : chunks) {
                    selected.add(chunk.key());
                }
                // The origin sits on the widest cubes' grid, so every ring's cubes count whole from it.
                int originX = Math.floorDiv((int) Math.floor(cameraCloudX), CloudRings.WIDEST_CUBE) * CloudRings.WIDEST_CUBE / DynamicClouds.CUBE_BLOCKS;
                int originZ = Math.floorDiv((int) Math.floor(cameraCloudZ), CloudRings.WIDEST_CUBE) * CloudRings.WIDEST_CUBE / DynamicClouds.CUBE_BLOCKS;
                List<CompletableFuture<ChunkMesh>> meshes = new ArrayList<>(chunks.size());
                for (CloudRings.Chunk chunk : chunks) {
                    int seams = seams(chunk, selected);
                    meshes.add(CompletableFuture.supplyAsync(() -> meshChunk(field, request, chunk, seams), workers));
                }
                // What the last generation drew that this one doesn't: it fades out over the change.
                List<CompletableFuture<ChunkMesh>> leaving = new ArrayList<>();
                if (request.previous != null) {
                    for (Map.Entry<Long, Generation.Chunk> entry : request.previous.chunks().entrySet()) {
                        if (!selected.contains(entry.getKey())) {
                            Generation.Chunk old = entry.getValue();
                            leaving.add(CompletableFuture.supplyAsync(() -> leavingChunk(old), workers));
                        }
                    }
                }
                List<CompletableFuture<ChunkMesh>> all = new ArrayList<>(meshes);
                all.addAll(leaving);
                return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(done -> {
                    List<ChunkMesh> finished = new ArrayList<>(meshes.size());
                    for (CompletableFuture<ChunkMesh> mesh : meshes) {
                        finished.add(mesh.join());
                    }
                    List<ChunkMesh> fading = new ArrayList<>(leaving.size());
                    for (CompletableFuture<ChunkMesh> mesh : leaving) {
                        fading.add(mesh.join());
                    }
                    return join(request, field, finished, fading, originX, originZ, cameraCloudX, cameraCloudZ, targetIndex, target, started);
                });
            });
    }

    private static CloudSky sky(Request request) {
        // Formations are kept a chunk beyond the reach, so a cloud just outside it is already there to drift in.
        double reach = request.reach + 2.0 * CHUNK_BLOCKS;
        if (request.storms != null) {
            return RegionalSky.sky(request.seed, request.floor, request.tick, request.storms, request.wind, request.cameraX,
                request.cameraZ, reach);
        }
        CloudSky own = VanillaWeatherSky.sky(request.seed, request.floor, request.tick, request.rain, request.thunder,
            request.cameraX, request.cameraZ, reach);
        // Under a weather mod's wind (a previewed weather over Almanac's regional weather), its clouds ride that wind too.
        return own.wind().equals(request.wind) ? own : new CloudSky(own.seed(), own.baseHeight(), request.wind, own.formations());
    }

    /** The sides of a chunk whose neighbour of the same cube size isn't in this build (another ring's, or beyond the reach). */
    private static int seams(CloudRings.Chunk chunk, Set<Long> selected) {
        int seams = 0;
        for (int side = FaceFormat.NORTH; side <= FaceFormat.EAST; side++) {
            int neighbourX = chunk.x() + (side == FaceFormat.WEST ? -1 : side == FaceFormat.EAST ? 1 : 0);
            int neighbourZ = chunk.z() + (side == FaceFormat.NORTH ? -1 : side == FaceFormat.SOUTH ? 1 : 0);
            if (!selected.contains(CloudRings.key(chunk.level(), neighbourX, neighbourZ))) {
                seams |= 1 << side;
            }
        }
        return seams;
    }

    /**
     * A chunk's faces for this build.
     *
     * @param chunk the chunk, its ring and how far it is from the camera
     * @param fade  whether it fades in over the change (it is new to this build)
     */
    private record ChunkMesh(CloudRings.Chunk chunk, Generation.Chunk kept, FaceList faces, int fade, boolean sampled, long nanos) {
    }

    private static ChunkMesh meshChunk(CloudField field, Request request, CloudRings.Chunk chunk, int seams) {
        long start = System.nanoTime();
        Generation previous = request.previous;
        Generation.Chunk before = previous != null ? previous.chunks().get(chunk.key()) : null;
        if (before != null && !CloudRings.due(chunk, request.number)) {
            // Not due: the chunk as it was, all of it standing still.
            FaceList faces = before.stableFaces(seams);
            Generation.Chunk kept = new Generation.Chunk(before.cells(), seams, faces, before.sampledAt());
            return new ChunkMesh(chunk, kept, faces, FaceFormat.FADE_NONE, false, System.nanoTime() - start);
        }
        int cube = chunk.cubeBlocks();
        double chunkBlocks = (double) CloudCells.SIZE * cube;
        double minX = chunk.x() * chunkBlocks;
        double minZ = chunk.z() * chunkBlocks;
        // The apron's columns, two cubes outside the chunk all round, are sampled too.
        double apron = (double) CloudCells.APRON * cube;
        CloudField.Area area = field.area(request.tick, minX - apron, minZ - apron, minX + chunkBlocks + apron, minZ + chunkBlocks + apron);
        CloudCells cells = area.formationCount() == 0 ? CloudCells.empty(chunk.x(), chunk.z(), cube)
            : CloudCells.sample(area, chunk.x(), chunk.z(), request.floor, cube, new CloudColumn());
        if (before != null) {
            FaceList faces = new FaceList(512);
            CloudMesher.mesh(before.cells(), cells, seams, faces);
            Generation.Chunk kept = new Generation.Chunk(cells, seams, null, request.number);
            return new ChunkMesh(chunk, kept, faces, FaceFormat.FADE_NONE, true, System.nanoTime() - start);
        }
        // New to the reach or to this ring: drawn whole, fading in (unless there is nothing to fade from).
        FaceList faces = new FaceList(512);
        CloudMesher.mesh(null, cells, seams, faces);
        Generation.Chunk kept = new Generation.Chunk(cells, seams, faces, request.number);
        int fade = previous != null ? FaceFormat.FADE_IN : FaceFormat.FADE_NONE;
        return new ChunkMesh(chunk, kept, faces, fade, true, System.nanoTime() - start);
    }

    private static ChunkMesh leavingChunk(Generation.Chunk old) {
        long start = System.nanoTime();
        FaceList faces = old.stableFaces(old.seams());
        CloudCells cells = old.cells();
        CloudRings.Chunk chunk = new CloudRings.Chunk(CloudRings.levelOf(cells.cubeBlocks()), cells.chunkX(), cells.chunkZ(), 0.0);
        return new ChunkMesh(chunk, old, faces, FaceFormat.FADE_OUT, false, System.nanoTime() - start);
    }

    private Generation join(Request request, CloudField field, List<ChunkMesh> meshes, List<ChunkMesh> leaving, int originX, int originZ,
                            double cameraCloudX, double cameraCloudZ, int targetIndex, ByteBuffer target, long started) {
        // Nearest first, so the nearest faces fill the depth buffer first.
        meshes.sort(Comparator.comparingDouble(mesh -> mesh.chunk.distance()));
        int[] budget = CloudRings.mostFaces();
        int[] facesByRing = new int[CloudRings.RINGS];
        int[] keptByRing = new int[CloudRings.RINGS];
        int total = 0;
        long chunkNanos = 0L;
        long slowestChunk = 0L;
        int sampled = 0;
        List<ChunkMesh> drawn = new ArrayList<>(meshes.size() + leaving.size());
        for (ChunkMesh mesh : meshes) {
            int ring = mesh.chunk.level();
            int size = mesh.faces.size();
            total += size;
            facesByRing[ring] += size;
            chunkNanos += mesh.nanos;
            slowestChunk = Math.max(slowestChunk, mesh.nanos);
            sampled += mesh.sampled ? 1 : 0;
            // Past a ring's face budget its farthest chunks are left out (they come last).
            if (keptByRing[ring] + size <= budget[ring]) {
                keptByRing[ring] += size;
                drawn.add(mesh);
            }
        }
        for (ChunkMesh mesh : leaving) {
            int ring = mesh.chunk.level();
            if (keptByRing[ring] + mesh.faces.size() <= budget[ring]) {
                keptByRing[ring] += mesh.faces.size();
                drawn.add(mesh);
            }
        }
        int faceCount = 0;
        for (ChunkMesh mesh : drawn) {
            faceCount += mesh.faces.size();
        }
        int bytes = faceCount * FaceFormat.BYTES;
        if (target == null || target.capacity() < bytes) {
            target = ByteBuffer.allocateDirect(Math.max(bytes + bytes / 2, 64 * 1024)).order(ByteOrder.nativeOrder());
            joined[targetIndex] = target;
        }
        target.clear();
        int moving = 0;
        for (ChunkMesh mesh : drawn) {
            int scale = mesh.chunk.cubeBlocks() / DynamicClouds.CUBE_BLOCKS;
            int baseX = mesh.chunk.x() * CloudCells.SIZE - originX / scale;
            int baseZ = mesh.chunk.z() * CloudCells.SIZE - originZ / scale;
            mesh.faces.writeTo(target, baseX, baseZ, mesh.fade);
            if (mesh.fade != FaceFormat.FADE_NONE) {
                moving += mesh.faces.size();
            } else if (mesh.sampled) {
                for (int i = 0; i < mesh.faces.size(); i++) {
                    if (FaceFormat.state(mesh.faces.look(i)) != FaceFormat.STABLE) {
                        moving++;
                    }
                }
            }
        }
        target.flip();
        Map<Long, Generation.Chunk> chunks = new HashMap<>();
        List<CloudCells> cells = new ArrayList<>(meshes.size());
        for (ChunkMesh mesh : meshes) {
            chunks.put(mesh.chunk.key(), mesh.kept);
            cells.add(mesh.kept.cells());
        }
        // The shadow map is centred on the faces' origin, in blocks of cloud space.
        long shadowStarted = System.nanoTime();
        int shadowCornerX = originX * DynamicClouds.CUBE_BLOCKS - CloudShadowMap.SPAN_BLOCKS / 2;
        int shadowCornerZ = originZ * DynamicClouds.CUBE_BLOCKS - CloudShadowMap.SPAN_BLOCKS / 2;
        byte[] shadow = shadowMap.build(cells, shadowCornerX, shadowCornerZ);
        long shadowNanos = System.nanoTime() - shadowStarted;
        long buildNanos = System.nanoTime() - started;
        return new Generation(request.tick, request.floor, field.driftX(request.tick), field.driftZ(request.tick), originX, originZ,
            cameraCloudX, cameraCloudZ, request.cameraX, request.cameraZ, chunks, target, faceCount, total, moving, meshes.size(), sampled,
            facesByRing, keptByRing, shadow, shadowCornerX, shadowCornerZ, buildNanos / 1.0e6, chunkNanos / 1.0e6, slowestChunk / 1.0e6,
            shadowNanos / 1.0e6);
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
