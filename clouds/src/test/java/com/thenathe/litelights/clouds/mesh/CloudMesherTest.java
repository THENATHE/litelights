package com.thenathe.litelights.clouds.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.clouds.field.CloudColumn;
import com.thenathe.litelights.clouds.field.CloudField;
import com.thenathe.litelights.clouds.field.VanillaWeatherSky;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The mesher: which faces it draws, how changing cubes grow, and that the change starts and ends
 * exactly on the two builds, so nothing switches on or off between generations.
 */
class CloudMesherTest {
    private static final int CUBE = 8;

    @Test
    void aLoneCubeHasSixFacesAndTwoTouchingOnesTen() {
        CloudCells one = CloudCells.of(0, 0, CUBE, (x, z, y) -> x == 3 && z == 4 && y == 5);
        assertEquals(6, mesh(null, one).size());
        CloudCells two = CloudCells.of(0, 0, CUBE, (x, z, y) -> (x == 3 || x == 4) && z == 4 && y == 5);
        FaceList faces = mesh(null, two);
        assertEquals(10, faces.size());
        for (int i = 0; i < faces.size(); i++) {
            assertEquals(FaceFormat.STABLE, FaceFormat.state(faces.look(i)));
        }
    }

    @Test
    void withNothingBeforeEveryExposedSideIsCoveredOnce() {
        Random random = new Random(7);
        boolean[][][] solid = new boolean[CloudCells.SIZE + 2][CloudCells.SIZE + 2][24];
        for (boolean[][] plane : solid) {
            for (boolean[] column : plane) {
                for (int y = 0; y < column.length; y++) {
                    column[y] = random.nextFloat() < 0.4f;
                }
            }
        }
        CloudCells cells = CloudCells.of(0, 0, CUBE, (x, z, y) -> y < 24 && solid[x + 1][z + 1][y]);
        int exposed = 0;
        for (int x = 0; x < CloudCells.SIZE; x++) {
            for (int z = 0; z < CloudCells.SIZE; z++) {
                for (int y = 0; y < 24; y++) {
                    if (!cells.solid(x, z, y)) {
                        continue;
                    }
                    for (int side = 0; side < 6; side++) {
                        if (!cells.solid(x + CloudMesher.dx(side), z + CloudMesher.dz(side), y + CloudMesher.dy(side))) {
                            exposed++;
                        }
                    }
                }
            }
        }
        Map<Long, Integer> covered = coverage(mesh(null, cells));
        int coveredSides = 0;
        for (int count : covered.values()) {
            assertEquals(1, count, "no cell side is covered twice");
            coveredSides += count;
        }
        assertEquals(exposed, coveredSides);
    }

    @Test
    void aStableWallIsJoinedIntoTallFaces() {
        CloudCells tower = CloudCells.of(0, 0, CUBE, (x, z, y) -> x == 5 && z == 5 && y >= 4 && y <= 4 + CloudMesher.MOST_JOINED - 1);
        FaceList faces = mesh(null, tower);
        assertEquals(6, faces.size(), "a top, a base and one face on each side");
        for (int i = 0; i < faces.size(); i++) {
            int side = FaceFormat.side(faces.position(i));
            assertEquals(side >= FaceFormat.NORTH ? CloudMesher.MOST_JOINED : 1, FaceFormat.cells(faces.word3(i)));
        }
        CloudCells tall = CloudCells.of(0, 0, CUBE, (x, z, y) -> x == 5 && z == 5 && y < 40);
        // Joined faces are at most MOST_JOINED cells tall.
        int wallFaces = mesh(null, tall).size();
        // Joins break on multiples of MOST_JOINED in every column, so neighbouring walls share corners.
        assertEquals(2 + 4 * ((40 + CloudMesher.MOST_JOINED - 1) / CloudMesher.MOST_JOINED), wallFaces, "no joined face is taller than the most");
    }

    @Test
    void aSeamClosesTheChunksOwnEdge() {
        CloudCells slab = CloudCells.of(0, 0, CUBE, (x, z, y) -> y == 3);
        FaceList open = mesh(null, slab);
        assertTrue(!hasFace(open, CloudCells.SIZE - 1, 7, 3, FaceFormat.EAST), "cloud beyond the edge, so no face toward it");
        FaceList closed = new FaceList();
        CloudMesher.mesh(null, slab, 1 << FaceFormat.EAST, closed);
        for (int z = 0; z < CloudCells.SIZE; z++) {
            assertTrue(hasFace(closed, CloudCells.SIZE - 1, z, 3, FaceFormat.EAST), "toward another cube size's chunk the edge is drawn");
            assertTrue(!hasFace(closed, 0, z, 3, FaceFormat.WEST), "only on the seam's side");
        }
    }

    @Test
    void shadingNormalsFollowTheCloudsShape() {
        // A dome: its top faces lean away from its middle, a flat slab's look straight up.
        CloudCells dome = CloudCells.of(0, 0, CUBE, (x, z, y) -> {
            double dx = x - 15.5;
            double dz = z - 15.5;
            return y <= 10 - (dx * dx + dz * dz) / 12.0;
        });
        FaceList faces = mesh(null, dome);
        float eastLean = 0.0f;
        float upward = 1.0f;
        for (int i = 0; i < faces.size(); i++) {
            int position = faces.position(i);
            if (FaceFormat.side(position) != FaceFormat.UP || FaceFormat.z(position) != 15) {
                continue;
            }
            float[] normal = cornerAverage(faces, i);
            if (FaceFormat.x(position) >= 20) {
                eastLean = Math.max(eastLean, normal[0]);
            }
            if (FaceFormat.x(position) == 15) {
                upward = Math.min(upward, normal[1]);
            }
        }
        assertTrue(eastLean > 0.3f, "the dome's east shoulder leans east: " + eastLean);
        assertTrue(upward > 0.9f, "its crown looks up: " + upward);
        CloudCells slab = CloudCells.of(0, 0, CUBE, (x, z, y) -> y >= 3 && y <= 5);
        FaceList slabFaces = mesh(null, slab);
        for (int i = 0; i < slabFaces.size(); i++) {
            int position = slabFaces.position(i);
            // Away from the chunk's edge, where the test chunk's outer apron ring is left empty.
            if (FaceFormat.side(position) == FaceFormat.UP && FaceFormat.x(position) == 10 && FaceFormat.z(position) >= 3
                && FaceFormat.z(position) <= CloudCells.SIZE - 4) {
                assertEquals(1.0f, cornerAverage(slabFaces, i)[1], 0.02f);
            }
        }
    }

    @Test
    void facesKnowHowHighTheirCloudReaches() {
        FaceList faces = mesh(null, column(2, 9));
        for (int i = 0; i < faces.size(); i++) {
            for (int edge = 0; edge < 2; edge++) {
                assertEquals(10.0f * CUBE, FaceFormat.edgeTop(faces.word3(i), edge), 1.01f, "the column's run reaches the top of cell 9");
            }
        }
    }

    @Test
    void cornersSharedByFacesShadeAlike() {
        // Two faces meeting at a corner (a top and the side under its edge) take the same normal there.
        CloudCells dome = CloudCells.of(0, 0, CUBE, (x, z, y) -> {
            double dx = x - 15.5;
            double dz = z - 15.5;
            return y <= 8 - (dx * dx + dz * dz) / 20.0;
        });
        FaceList faces = mesh(null, dome);
        Map<Long, float[]> seen = new HashMap<>();
        int shared = 0;
        for (int i = 0; i < faces.size(); i++) {
            int position = faces.position(i);
            int side = FaceFormat.side(position);
            for (int corner = 0; corner < 4; corner++) {
                int[] offset = FaceFormat.CORNERS[side * 4 + corner];
                long key = cellKey(FaceFormat.x(position) + offset[0], FaceFormat.z(position) + offset[2],
                    FaceFormat.y(position) + offset[1] * FaceFormat.cells(faces.word3(i)));
                float[] normal = FaceFormat.direction(side, FaceFormat.cornerNormal(faces.word2(i), faces.word3(i), corner));
                float[] before = seen.putIfAbsent(key, normal);
                if (before != null) {
                    shared++;
                    float cosine = before[0] * normal[0] + before[1] * normal[1] + before[2] * normal[2];
                    // Each face codes the normal in its own frame, within a few degrees; one leaning into a face lies flat on it.
                    assertTrue(cosine > Math.cos(Math.toRadians(50.0)), "faces meeting at a corner shade alike: " + cosine);
                }
            }
        }
        assertTrue(shared > 100);
    }

    /** The average of a face's four corner normals. */
    private static float[] cornerAverage(FaceList faces, int face) {
        float[] sum = new float[3];
        for (int corner = 0; corner < 4; corner++) {
            int side = FaceFormat.side(faces.position(face));
            float[] normal = FaceFormat.direction(side, FaceFormat.cornerNormal(faces.word2(face), faces.word3(face), corner));
            for (int axis = 0; axis < 3; axis++) {
                sum[axis] += normal[axis] / 4.0f;
            }
        }
        return sum;
    }

    @Test
    void aGrowingTowerRisesCellAfterCellAtAnEvenPace() {
        CloudCells before = column(0, 2);
        CloudCells after = column(0, 9);
        Map<Integer, Integer> looks = looksByCell(mesh(before, after), 5, 5);
        for (float progress = 0.0f; progress <= 1.0f; progress += 0.05f) {
            float height = 3.0f;
            boolean frontPassed = false;
            for (int y = 3; y <= 9; y++) {
                float[] box = CubeGrowth.box(looks.get(y), progress);
                if (box == null) {
                    frontPassed = true;
                    continue;
                }
                assertTrue(!frontPassed, "a cell above the rising top showed at " + progress);
                assertEquals(0.0f, box[1], 1.0e-6f, "every cell of the tower grows up from the one below");
                height += box[4] - box[1];
            }
            assertEquals(3.0f + 7.0f * progress, height, 0.02f, "the top rises evenly at " + progress);
        }
    }

    @Test
    void cellsRisingInStepShareNoFace() {
        CloudCells before = CloudCells.of(0, 0, CUBE, (x, z, y) -> x >= 4 && x <= 6 && z >= 4 && z <= 6 && y <= 1);
        CloudCells after = CloudCells.of(0, 0, CUBE, (x, z, y) -> x >= 4 && x <= 6 && z >= 4 && z <= 6 && y <= 4);
        FaceList faces = mesh(before, after);
        for (int y = 2; y <= 4; y++) {
            assertTrue(!hasFace(faces, 5, 5, y, FaceFormat.EAST), "no face between two cells rising together");
            assertTrue(hasFace(faces, 6, 5, y, FaceFormat.EAST), "the slab's outside keeps its faces");
        }
    }

    @Test
    void aShrinkingTowerSinksFromTheTop() {
        Map<Integer, Integer> looks = looksByCell(mesh(column(0, 9), column(0, 2)), 5, 5);
        float[] top = CubeGrowth.box(looks.get(9), 0.1f);
        float[] lowest = CubeGrowth.box(looks.get(3), 0.1f);
        assertTrue(top == null || top[4] < 1.0f, "the top cell empties first");
        assertNotNull(lowest);
        assertEquals(1.0f, lowest[4], 1.0e-6f, "the lowest changing cell is still full early on");
        assertNull(CubeGrowth.box(looks.get(9), 1.0f), "gone at the end");
    }

    @Test
    void aCloudWideningGrowsOutOfItsSide() {
        CloudCells before = CloudCells.of(0, 0, CUBE, (x, z, y) -> x == 5 && z == 5 && y == 4);
        CloudCells after = CloudCells.of(0, 0, CUBE, (x, z, y) -> (x == 5 || x == 6) && z == 5 && y == 4);
        FaceList faces = mesh(before, after);
        int look = looksByCell(faces, 6, 5).get(4);
        assertEquals(FaceFormat.WEST, FaceFormat.growFrom(look), "it grows out of the stable cube to its west");
        float[] half = CubeGrowth.box(look, 0.5f);
        assertEquals(0.0f, half[0], 1.0e-6f);
        assertEquals(0.5f, half[3], 0.01f);
        // The stable cube keeps its face toward the growing one, so there is always a surface there.
        assertTrue(hasFace(faces, 5, 5, 4, FaceFormat.EAST));
    }

    @Test
    void aNewPuffGrowsFromItsMiddleOutward() {
        CloudCells before = CloudCells.empty(0, 0, CUBE);
        CloudCells after = CloudCells.of(0, 0, CUBE, (x, z, y) -> x == 10 && z == 10 && y >= 4 && y <= 6);
        Map<Integer, Integer> looks = looksByCell(mesh(before, after), 10, 10);
        int middle = looks.get(5);
        assertEquals(-1, FaceFormat.growFrom(middle));
        assertEquals(FaceFormat.DOWN, FaceFormat.growFrom(looks.get(6)), "the top cell grows up from the middle one");
        assertEquals(FaceFormat.UP, FaceFormat.growFrom(looks.get(4)), "the bottom cell grows down from it");
        assertTrue(FaceFormat.widens(looks.get(6)));
        assertTrue(CubeGrowth.volume(middle, 0.02f) < 0.001f, "it starts as nothing");
    }

    /**
     * For any two builds, the change starts exactly on the first (p = 0) and ends exactly on the
     * second (p = 1): the cubes drawn at each end are the cells solid in that build, so the swap to
     * the next generation, which starts from this one's end, changes nothing on screen.
     */
    @Test
    void theChangeStartsAndEndsOnTheTwoBuilds() {
        Random random = new Random(11);
        for (int trial = 0; trial < 20; trial++) {
            long seed = random.nextLong();
            CloudCells before = blobs(seed, 0.0f);
            CloudCells after = blobs(seed, 0.15f + 0.1f * random.nextFloat());
            FaceList faces = mesh(before, after);
            Map<Long, Integer> looks = new HashMap<>();
            for (int i = 0; i < faces.size(); i++) {
                int position = faces.position(i);
                looks.put(cellKey(FaceFormat.x(position), FaceFormat.z(position), FaceFormat.y(position)), faces.look(i));
            }
            for (Map.Entry<Long, Integer> entry : looks.entrySet()) {
                int x = (int) (entry.getKey() >> 40);
                int z = (int) (entry.getKey() >> 20 & 0xFFFFF);
                int y = (int) (entry.getKey() & 0xFFFFF);
                float start = CubeGrowth.volume(entry.getValue(), 0.0f);
                float end = CubeGrowth.volume(entry.getValue(), 1.0f);
                assertEquals(before.solid(x, z, y) ? 1.0f : 0.0f, start, 0.02f, "the change starts on the first build");
                assertEquals(after.solid(x, z, y) ? 1.0f : 0.0f, end, 0.02f, "the change ends on the second build");
            }
        }
    }

    @Test
    void chunksSampledFromTheFieldMeetWithoutASeam() {
        CloudField field = new CloudField(VanillaWeatherSky.sky(0x51CE5EEDL, 192.0f, 30_000.0, 0.6f, 0.0f, 0.0, 0.0, 4_096.0));
        int chunkBlocks = CloudCells.SIZE * CUBE;
        CloudCells west = sample(field, -1, 0, chunkBlocks);
        CloudCells east = sample(field, 0, 0, chunkBlocks);
        int borderCells = 0;
        for (int z = 0; z < CloudCells.SIZE; z++) {
            for (int y = 0; y < CloudCells.HEIGHT; y++) {
                assertEquals(east.solid(0, z, y), west.solid(CloudCells.SIZE, z, y), "the west chunk's apron is the east chunk's edge");
                assertEquals(west.solid(CloudCells.SIZE - 1, z, y), east.solid(-1, z, y), "and the other way");
                if (east.solid(0, z, y)) {
                    borderCells++;
                }
            }
        }
        assertTrue(borderCells > 0, "the test sky should have cloud on the border");
        // A face on the border is drawn by the chunk whose cell it is, and only where the other side is clear.
        FaceList westFaces = mesh(null, west);
        for (int z = 0; z < CloudCells.SIZE; z++) {
            for (int y = 0; y < CloudCells.HEIGHT; y++) {
                boolean expected = west.solid(CloudCells.SIZE - 1, z, y) && !east.solid(0, z, y);
                assertEquals(expected, hasFace(westFaces, CloudCells.SIZE - 1, z, y, FaceFormat.EAST),
                    "border face at z " + z + ", y " + y);
            }
        }
    }

    private static CloudCells sample(CloudField field, int chunkX, int chunkZ, int chunkBlocks) {
        double minX = (double) chunkX * chunkBlocks;
        double minZ = (double) chunkZ * chunkBlocks;
        CloudField.Area area = field.area(30_000.0, minX - 2 * CUBE, minZ - 2 * CUBE, minX + chunkBlocks + 2 * CUBE, minZ + chunkBlocks + 2 * CUBE);
        return CloudCells.sample(area, chunkX, chunkZ, 192.0f, CUBE, new CloudColumn());
    }

    /** Lumpy blobs from a seed, grown by {@code growth} (a share of a cell's reach). */
    private static CloudCells blobs(long seed, float growth) {
        Random random = new Random(seed);
        double[][] centres = new double[6][4];
        for (double[] centre : centres) {
            centre[0] = random.nextDouble() * 34.0 - 1.0;
            centre[1] = random.nextDouble() * 34.0 - 1.0;
            centre[2] = 4.0 + random.nextDouble() * 10.0;
            centre[3] = 2.0 + random.nextDouble() * 5.0;
        }
        return CloudCells.of(0, 0, CUBE, (x, z, y) -> {
            for (double[] centre : centres) {
                double dx = x + 0.5 - centre[0];
                double dz = z + 0.5 - centre[1];
                double dy = (y + 0.5 - centre[2]) * 1.6;
                double reach = centre[3] * (1.0 + growth);
                if (dx * dx + dz * dz + dy * dy < reach * reach) {
                    return true;
                }
            }
            return false;
        });
    }

    private static CloudCells column(int bottom, int top) {
        return CloudCells.of(0, 0, CUBE, (x, z, y) -> x == 5 && z == 5 && y >= bottom && y <= top);
    }

    private static FaceList mesh(CloudCells before, CloudCells after) {
        FaceList faces = new FaceList();
        CloudMesher.mesh(before, after, 0, faces);
        return faces;
    }

    /** How many faces cover each cell's side, a joined face counting for every cell it spans. */
    private static Map<Long, Integer> coverage(FaceList faces) {
        Map<Long, Integer> covered = new HashMap<>();
        for (int i = 0; i < faces.size(); i++) {
            int position = faces.position(i);
            for (int cell = 0; cell < FaceFormat.cells(faces.word3(i)); cell++) {
                long key = cellKey(FaceFormat.x(position), FaceFormat.z(position), FaceFormat.y(position) + cell) * 8 + FaceFormat.side(position);
                covered.merge(key, 1, Integer::sum);
            }
        }
        return covered;
    }

    /** The look of each cell of one column that has a face. */
    private static Map<Integer, Integer> looksByCell(FaceList faces, int x, int z) {
        Map<Integer, Integer> looks = new HashMap<>();
        for (int i = 0; i < faces.size(); i++) {
            int position = faces.position(i);
            if (FaceFormat.x(position) == x && FaceFormat.z(position) == z) {
                looks.put(FaceFormat.y(position), faces.look(i));
            }
        }
        return looks;
    }

    /** Whether a face covers this cell's side (a joined face covers every cell it spans). */
    private static boolean hasFace(FaceList faces, int x, int z, int y, int side) {
        for (int i = 0; i < faces.size(); i++) {
            int position = faces.position(i);
            int first = FaceFormat.y(position);
            if (FaceFormat.x(position) == x && FaceFormat.z(position) == z && FaceFormat.side(position) == side
                && y >= first && y < first + FaceFormat.cells(faces.word3(i))) {
                return true;
            }
        }
        return false;
    }

    private static long cellKey(int x, int z, int y) {
        return (long) x << 40 | (long) z << 20 | y;
    }
}
