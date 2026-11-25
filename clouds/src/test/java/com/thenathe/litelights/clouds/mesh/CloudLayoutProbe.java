package com.thenathe.litelights.clouds.mesh;

import com.thenathe.litelights.clouds.field.CloudColumn;
import com.thenathe.litelights.clouds.field.CloudField;
import com.thenathe.litelights.clouds.field.VanillaWeatherSky;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A whole build of the reach laid out with other near radii than {@link CloudRings#NEAR_RING}, at
 * this build's chunk size ({@link CloudCells#SIZE}), on one thread: chunks, columns sampled (the
 * apron included), faces and cover by ring, and the time to sample and mesh it all, for a clear
 * sky, a settled rain deck, thunder and a deck closing. Printed only, and only with
 * {@code -Plitelights.clouds.probe=true}; the near radii with {@code -Plitelights.clouds.layoutNear=512,1024}
 * (docs/DECISIONS.md, the tidy-up for 1.0.14). The chunk size is a constant, so another chunk
 * size is probed by building with another {@code CloudCells.SIZE}.
 */
class CloudLayoutProbe {
    private static final long SEED = 0x51CE5EEDL;
    private static final float FLOOR = 192.0f;
    private static final double TICK = 30_000.0;
    private static final int REACH = 8_192;
    /** A build samples a far ring's chunk every this many builds ({@code CloudRings.SAMPLE_EVERY}). */
    private static final int[] SAMPLE_EVERY = {1, 2, 4, 8};
    /** Cover is counted in these bands of distance from the camera, in blocks. */
    private static final int[] BANDS = {512, 1_024, 2_048, 4_096, 8_192};

    @Test
    void layouts() {
        Assumptions.assumeTrue(Boolean.getBoolean("litelights.clouds.probe"), "a probe, run only when asked for");
        String near = System.getProperty("litelights.clouds.layoutNear", "512,1024,2048");
        System.out.println(String.format(Locale.ROOT, "chunks of %d cubes", CloudCells.SIZE));
        for (String weather : new String[] {"clear", "rain", "thunder", "closing"}) {
            for (String radius : near.split(",")) {
                probe(weather, Integer.parseInt(radius.trim()));
            }
        }
    }

    private static void probe(String weather, int nearRing) {
        float rain = switch (weather) {
            case "clear" -> 0.0f;
            case "closing" -> 0.45f;
            default -> 1.0f;
        };
        float thunder = weather.equals("thunder") ? 1.0f : 0.0f;
        double padding = 2.0 * CloudCells.SIZE * (CloudRings.NEAR_CUBE << (CloudRings.RINGS - 1));
        CloudField field = new CloudField(VanillaWeatherSky.sky(SEED, FLOOR, TICK, rain, thunder, 0.0, 0.0, REACH + padding));
        // While a deck closes a build meshes against the one a second (20 ticks) before.
        CloudField before = weather.equals("closing")
            ? new CloudField(VanillaWeatherSky.sky(SEED, FLOOR, TICK - 20.0, rain - 20.0f / 700.0f, 0.0f, 0.0, 0.0, REACH + padding))
            : null;
        List<CloudRings.Chunk> chunks = select(nearRing);
        int[] chunksByRing = new int[CloudRings.RINGS];
        long[] facesByRing = new long[CloudRings.RINGS];
        long[] nanosByRing = new long[CloudRings.RINGS];
        long columns = 0;
        double[] coveredByBand = new double[BANDS.length];
        double[] areaByBand = new double[BANDS.length];
        // One round to warm up, then the measured one.
        for (int round = 0; round < 2; round++) {
            Arrays.fill(chunksByRing, 0);
            Arrays.fill(facesByRing, 0);
            Arrays.fill(nanosByRing, 0);
            Arrays.fill(coveredByBand, 0.0);
            Arrays.fill(areaByBand, 0.0);
            columns = 0;
            for (CloudRings.Chunk chunk : chunks) {
                int cube = chunk.cubeBlocks();
                double chunkBlocks = (double) CloudCells.SIZE * cube;
                double minX = chunk.x() * chunkBlocks;
                double minZ = chunk.z() * chunkBlocks;
                double apron = (double) CloudCells.APRON * cube;
                long started = System.nanoTime();
                CloudCells cells = sample(field, chunk, minX, minZ, chunkBlocks, apron, cube);
                CloudCells earlier = before == null ? null : sample(before, chunk, minX, minZ, chunkBlocks, apron, cube);
                FaceList faces = new FaceList(512);
                CloudMesher.mesh(earlier, cells, seams(chunk, chunks), faces);
                long took = System.nanoTime() - started;
                chunksByRing[chunk.level()]++;
                facesByRing[chunk.level()] += faces.size();
                nanosByRing[chunk.level()] += before == null ? took : took / 2;
                int span = CloudCells.SIZE + 2 * CloudCells.APRON;
                columns += (long) span * span;
                for (int z = 0; z < CloudCells.SIZE; z++) {
                    for (int x = 0; x < CloudCells.SIZE; x++) {
                        double centreX = minX + (x + 0.5) * cube;
                        double centreZ = minZ + (z + 0.5) * cube;
                        double distance = Math.sqrt(centreX * centreX + centreZ * centreZ);
                        int band = band(distance);
                        if (band < 0) {
                            continue;
                        }
                        double area = (double) cube * cube;
                        areaByBand[band] += area;
                        if (cells.solidCount(x, z) > 0) {
                            coveredByBand[band] += area;
                        }
                    }
                }
            }
        }
        long faces = 0;
        double fullMillis = 0.0;
        double settledMillis = 0.0;
        StringBuilder rings = new StringBuilder();
        for (int level = 0; level < CloudRings.RINGS; level++) {
            faces += facesByRing[level];
            double millis = nanosByRing[level] / 1.0e6;
            fullMillis += millis;
            settledMillis += millis / SAMPLE_EVERY[level];
            rings.append(String.format(Locale.ROOT, " | ring %d (%d-block cubes): %d chunks, %d faces, %.0f ms", level,
                CloudRings.NEAR_CUBE << level, chunksByRing[level], facesByRing[level], millis));
        }
        StringBuilder cover = new StringBuilder();
        for (int band = 0; band < BANDS.length; band++) {
            cover.append(String.format(Locale.ROOT, " %s%.1f%%", band == 0 ? "" : "/", areaByBand[band] == 0.0 ? 0.0 : 100.0 * coveredByBand[band] / areaByBand[band]));
        }
        System.out.println(String.format(Locale.ROOT,
            "%s, near %d: %d chunks, %d columns sampled, %d faces, build %.0f ms (whole), %.0f ms (settled, far rings in turns); cover by band to 0.5/1/2/4/8 km:%s%s",
            weather, nearRing, chunks.size(), columns, faces, fullMillis, settledMillis, cover, rings));
    }

    private static CloudCells sample(CloudField field, CloudRings.Chunk chunk, double minX, double minZ, double chunkBlocks, double apron,
                                     int cube) {
        CloudField.Area area = field.area(TICK, minX - apron, minZ - apron, minX + chunkBlocks + apron, minZ + chunkBlocks + apron);
        return area.formationCount() == 0 ? CloudCells.empty(chunk.x(), chunk.z(), cube)
            : CloudCells.sample(area, chunk.x(), chunk.z(), FLOOR, cube, new CloudColumn());
    }

    private static int band(double distance) {
        for (int band = 0; band < BANDS.length; band++) {
            if (distance < BANDS[band]) {
                return band;
            }
        }
        return -1;
    }

    /** {@link CloudRings#select} with the near ring as a parameter, the camera at the origin. */
    private static List<CloudRings.Chunk> select(int nearRing) {
        int top = 0;
        while (top < CloudRings.RINGS - 1 && (nearRing << top) < REACH) {
            top++;
        }
        double topBlocks = (double) CloudCells.SIZE * (CloudRings.NEAR_CUBE << top);
        int first = (int) Math.floor(-REACH / topBlocks);
        int last = (int) Math.floor(REACH / topBlocks);
        List<CloudRings.Chunk> chunks = new ArrayList<>();
        for (int z = first; z <= last; z++) {
            for (int x = first; x <= last; x++) {
                add(nearRing, top, x, z, chunks);
            }
        }
        return chunks;
    }

    private static void add(int nearRing, int level, int x, int z, List<CloudRings.Chunk> chunks) {
        double blocks = (double) CloudCells.SIZE * (CloudRings.NEAR_CUBE << level);
        double nearestX = Math.clamp(0.0, x * blocks, (x + 1) * blocks);
        double nearestZ = Math.clamp(0.0, z * blocks, (z + 1) * blocks);
        double distance = Math.sqrt(nearestX * nearestX + nearestZ * nearestZ);
        if (distance > REACH) {
            return;
        }
        if (level > 0 && distance < (nearRing << (level - 1))) {
            for (int quarter = 0; quarter < 4; quarter++) {
                add(nearRing, level - 1, x * 2 + (quarter & 1), z * 2 + (quarter >> 1), chunks);
            }
            return;
        }
        chunks.add(new CloudRings.Chunk(level, x, z, distance));
    }

    private static int seams(CloudRings.Chunk chunk, List<CloudRings.Chunk> chunks) {
        int seams = 0;
        for (int side = FaceFormat.NORTH; side <= FaceFormat.EAST; side++) {
            int neighbourX = chunk.x() + (side == FaceFormat.WEST ? -1 : side == FaceFormat.EAST ? 1 : 0);
            int neighbourZ = chunk.z() + (side == FaceFormat.NORTH ? -1 : side == FaceFormat.SOUTH ? 1 : 0);
            boolean found = false;
            for (CloudRings.Chunk other : chunks) {
                if (other.level() == chunk.level() && other.x() == neighbourX && other.z() == neighbourZ) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                seams |= 1 << side;
            }
        }
        return seams;
    }
}
