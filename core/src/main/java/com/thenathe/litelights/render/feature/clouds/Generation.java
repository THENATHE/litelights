package com.thenathe.litelights.render.feature.clouds;

import com.thenathe.litelights.clouds.mesh.CloudCells;
import com.thenathe.litelights.clouds.mesh.CloudMesher;
import com.thenathe.litelights.clouds.mesh.CloudRings;
import com.thenathe.litelights.clouds.mesh.FaceList;
import java.nio.ByteBuffer;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One finished build of the clouds: the cells of every chunk in reach at one tick (far rings'
 * from the last build that sampled them), and their faces ready for the GPU, with the changes from
 * the generation before drawn as growth. Immutable once built, apart from the face bytes, which are
 * read once for the upload.
 *
 * @param tick         the game tick the clouds were built for
 * @param floor        the lowest cloud base, in world y
 * @param driftX       how far the wind had carried cloud space by that tick (world is cloud plus this)
 * @param originX      the faces' origin, in 8-block cubes of cloud space (on the widest cubes' grid)
 * @param cameraCloudX where the camera was in cloud space when it was built
 * @param cameraX      where the camera was in world space
 * @param chunks       each chunk, by {@link CloudRings#key}
 * @param faceCount    the faces in {@code faces}, the fading ones included
 * @param facesInReach the faces of every chunk in reach, before the budgets left any out
 * @param movingFaces  the faces that grow, shrink or fade in this generation's change
 * @param sampledChunks the chunks sampled from the field in this build (the rest were reused)
 * @param facesByRing  each ring's faces before its budget, and {@code keptByRing} after
 * @param shadow       how much sunlight each column lets through (clouds.mesh.CloudShadowMap), with
 *                     its corner in blocks of cloud space
 */
public record Generation(double tick, float floor, double driftX, double driftZ, int originX, int originZ,
                  double cameraCloudX, double cameraCloudZ, double cameraX, double cameraZ,
                  Map<Long, Chunk> chunks, ByteBuffer faces, int faceCount, int facesInReach, int movingFaces, int chunkCount,
                  int sampledChunks, int[] facesByRing, int[] keptByRing, byte[] shadow, int shadowCornerX, int shadowCornerZ,
                  double buildMillis, double chunkMillis, double slowestChunkMillis, double shadowMillis) {

    /**
     * One chunk as a generation keeps it: its cells, which of its sides close against another
     * ring, its faces standing still (made when first needed), and the build that sampled it.
     */
    static final class Chunk {
        private final CloudCells cells;
        private final int seams;
        private final long sampledAt;
        private @Nullable FaceList stable;

        Chunk(CloudCells cells, int seams, @Nullable FaceList stable, long sampledAt) {
            this.cells = cells;
            this.seams = seams;
            this.stable = stable;
            this.sampledAt = sampledAt;
        }

        CloudCells cells() {
            return cells;
        }

        int seams() {
            return seams;
        }

        long sampledAt() {
            return sampledAt;
        }

        /** Its faces with nothing changing, closed on the {@code seams} sides. Worker threads only, one at a time. */
        FaceList stableFaces(int seams) {
            FaceList faces = stable;
            if (faces != null && seams == this.seams) {
                return faces;
            }
            faces = new FaceList(512);
            CloudMesher.mesh(null, cells, seams, faces);
            if (seams == this.seams) {
                stable = faces;
            }
            return faces;
        }
    }

    /** The near ring's chunk holding a column of cloud space, or null if this generation didn't build it. */
    @Nullable CloudCells nearChunkAt(int cubeX, int cubeZ) {
        Chunk chunk = chunks.get(CloudRings.key(0, Math.floorDiv(cubeX, CloudCells.SIZE), Math.floorDiv(cubeZ, CloudCells.SIZE)));
        return chunk != null ? chunk.cells() : null;
    }

    /** Whether a cell of cloud space is solid, by 8-block cube column and height cell (near ring only). */
    boolean solid(int cubeX, int cubeZ, int cell) {
        CloudCells chunk = nearChunkAt(cubeX, cubeZ);
        if (chunk == null) {
            return false;
        }
        return chunk.solid(Math.floorMod(cubeX, CloudCells.SIZE), Math.floorMod(cubeZ, CloudCells.SIZE), cell);
    }

    /**
     * How much of a disc of 8-block columns around one holds cloud at any height, 0..1 (near ring
     * only; 0 where this generation didn't build it).
     */
    float coverAround(int cubeX, int cubeZ, int radius) {
        int covered = 0;
        int columns = 0;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                if (x * x + z * z > radius * radius) {
                    continue;
                }
                columns++;
                CloudCells chunk = nearChunkAt(cubeX + x, cubeZ + z);
                if (chunk != null && chunk.baseCell(Math.floorMod(cubeX + x, CloudCells.SIZE), Math.floorMod(cubeZ + z, CloudCells.SIZE))
                    < CloudCells.HEIGHT) {
                    covered++;
                }
            }
        }
        return covered / (float) columns;
    }

    /**
     * The highest top of the cloud the rain falls from, over a square of 8-block columns around one
     * (near ring only), in blocks above the floor, or -1 where none of them holds cloud. In each
     * column that is the top of its lowest run of cloud: a deck, or a tower rising out of it, but
     * not an anvil or a layer floating clear above them.
     */
    float rainCloudTopAround(int cubeX, int cubeZ, int radius) {
        float highest = -1.0f;
        for (int z = cubeZ - radius; z <= cubeZ + radius; z++) {
            for (int x = cubeX - radius; x <= cubeX + radius; x++) {
                CloudCells chunk = nearChunkAt(x, z);
                if (chunk == null) {
                    continue;
                }
                int localX = Math.floorMod(x, CloudCells.SIZE);
                int localZ = Math.floorMod(z, CloudCells.SIZE);
                int base = chunk.baseCell(localX, localZ);
                if (base < CloudCells.HEIGHT) {
                    highest = Math.max(highest, chunk.cloudTop(localX, localZ, base));
                }
            }
        }
        return highest;
    }
}
