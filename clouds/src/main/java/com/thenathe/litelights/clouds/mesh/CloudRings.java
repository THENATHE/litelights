package com.thenathe.litelights.clouds.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * How the reach is laid out in chunks: 8-block cubes out to {@link #NEAR_RING} blocks from the
 * camera, then rings of cubes twice as wide, each reaching twice as far (16-block cubes to 2 km,
 * 32 to 4 km, 64 beyond). A cloud far away is as many pixels wide as a near one is with cubes
 * that grow with distance, so each ring costs about three quarters of the faces of the one inside
 * it. A chunk is always {@link CloudCells#SIZE} cubes across, so a ring's chunks are each four of
 * the next ring in's, and the rings fit together as a quadtree: a chunk is split into its four
 * quarters wherever any of it lies within the ring inside.
 *
 * <p>Far rings change no faster than near ones, but are much smaller on screen, so they are
 * sampled again less often ({@link #due}); between times a build reuses their chunks as they were.
 * Each ring has its own face budget, so a sky thick with cloud loses some of its farthest chunks
 * in each ring rather than whole rings.
 */
public final class CloudRings {
    public static final int RINGS = 4;
    public static final int NEAR_CUBE = FaceFormat.CELL_BLOCKS;
    public static final int WIDEST_CUBE = NEAR_CUBE << (RINGS - 1);
    /** Where the first ring of wider cubes starts, in blocks from the camera. */
    public static final int NEAR_RING = 1_024;
    /** How often each ring's chunks are sampled again, in builds (about a second each). */
    private static final int[] SAMPLE_EVERY = {1, 2, 4, 8};
    /**
     * Each ring's face budget, so a thick sky loses some far chunks of each ring rather than whole
     * rings. A rain deck closing over the whole reach (the worst case, for about half a minute)
     * peaked at about 630,000 near faces and 500,000 to 610,000 in each far ring at 8 km; a settled
     * deck is about 280,000 near and 240,000 to 330,000 a ring.
     */
    private static final int[] MOST_FACES = {1_200_000, 800_000, 800_000, 800_000};

    private CloudRings() {
    }

    /**
     * One chunk of a build.
     *
     * @param level    its ring, 0 the nearest: cubes {@code NEAR_CUBE << level} blocks wide
     * @param x        the chunk, in chunks of its ring's size, in cloud space
     * @param distance how far its nearest point is from the camera, in blocks
     */
    public record Chunk(int level, int x, int z, double distance) {
        public long key() {
            return CloudRings.key(level, x, z);
        }

        public int cubeBlocks() {
            return NEAR_CUBE << level;
        }

        public double blocks() {
            return (double) CloudCells.SIZE * cubeBlocks();
        }
    }

    /** A chunk's key in a generation's map: its ring and place. */
    public static long key(int level, int x, int z) {
        return (long) level << 56 | ((long) x & 0xFFFFFFFL) << 28 | (long) z & 0xFFFFFFFL;
    }

    /** The ring whose cubes are this wide. */
    public static int levelOf(int cubeBlocks) {
        return Integer.numberOfTrailingZeros(cubeBlocks / NEAR_CUBE);
    }

    /** Where a ring ends, in blocks from the camera (the widest ring goes on to the reach). */
    public static int ringEdge(int level) {
        return NEAR_RING << level;
    }

    /** Each ring's face budget, a copy. */
    public static int[] mostFaces() {
        return MOST_FACES.clone();
    }

    /** The chunks within {@code reach} blocks of the camera (in cloud space), each in its ring, nearest first. */
    public static List<Chunk> select(double cameraX, double cameraZ, int reach) {
        int top = 0;
        while (top < RINGS - 1 && ringEdge(top) < reach) {
            top++;
        }
        double topBlocks = (double) CloudCells.SIZE * (NEAR_CUBE << top);
        int firstX = (int) Math.floor((cameraX - reach) / topBlocks);
        int lastX = (int) Math.floor((cameraX + reach) / topBlocks);
        int firstZ = (int) Math.floor((cameraZ - reach) / topBlocks);
        int lastZ = (int) Math.floor((cameraZ + reach) / topBlocks);
        List<Chunk> chunks = new ArrayList<>();
        for (int z = firstZ; z <= lastZ; z++) {
            for (int x = firstX; x <= lastX; x++) {
                add(top, x, z, cameraX, cameraZ, reach, chunks);
            }
        }
        chunks.sort((a, b) -> Double.compare(a.distance, b.distance));
        return chunks;
    }

    private static void add(int level, int x, int z, double cameraX, double cameraZ, int reach, List<Chunk> chunks) {
        double blocks = (double) CloudCells.SIZE * (NEAR_CUBE << level);
        double nearestX = Math.clamp(cameraX, x * blocks, (x + 1) * blocks);
        double nearestZ = Math.clamp(cameraZ, z * blocks, (z + 1) * blocks);
        double distance = Math.sqrt((nearestX - cameraX) * (nearestX - cameraX) + (nearestZ - cameraZ) * (nearestZ - cameraZ));
        if (distance > reach) {
            return;
        }
        if (level > 0 && distance < ringEdge(level - 1)) {
            for (int quarter = 0; quarter < 4; quarter++) {
                add(level - 1, x * 2 + (quarter & 1), z * 2 + (quarter >> 1), cameraX, cameraZ, reach, chunks);
            }
            return;
        }
        chunks.add(new Chunk(level, x, z, distance));
    }

    /**
     * Whether a chunk the last build had is due to be sampled again in build {@code build}. Each
     * chunk of a far ring takes its turn at its own place in the ring's cycle, so a build samples
     * about the same share of every ring.
     */
    public static boolean due(Chunk chunk, long build) {
        int every = SAMPLE_EVERY[chunk.level];
        if (every == 1) {
            return true;
        }
        int phase = Math.floorMod(chunk.x * 3 + chunk.z * 5, every);
        return Math.floorMod(build + phase, every) == 0;
    }
}
