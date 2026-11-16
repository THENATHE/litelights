package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.render.feature.weather.RainDetailRules.Block;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where drips and wall streaks form (RainDetailRules), in a small world of blocks on ground at y 64. */
class RainDetailRulesTest {
    /** Blocks by position; everything not set is air above y 64 and solid ground below. */
    private static final class TestWorld implements RainDetailRules.World {
        private final Map<Long, Block> blocks = new HashMap<>();
        private final Map<Long, RainDetailRules.StreakFace> faces = new HashMap<>();
        private final Map<Long, boolean[]> panes = new HashMap<>();

        void put(int x, int y, int z, Block block, boolean hardFaces) {
            blocks.put(key(x, y, z), block);
            if (hardFaces) {
                faces.put(key(x, y, z), RainDetailRules.StreakFace.HARD_WALL);
            }
        }

        void glass(int x0, int y0, int z0, int x1, int y1, int z1) {
            fill(x0, y0, z0, x1, y1, z1, Block.SOLID, false);
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        faces.put(key(x, y, z), RainDetailRules.StreakFace.GLASS_BLOCK);
                    }
                }
            }
        }

        /** A pane joining north, east, south and west as given. */
        void pane(int x, int y, int z, boolean north, boolean east, boolean south, boolean west) {
            put(x, y, z, Block.SOLID, false);
            panes.put(key(x, y, z), new boolean[] {north, east, south, west});
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block block, boolean hardFaces) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        put(x, y, z, block, hardFaces);
                    }
                }
            }
        }

        @Override
        public int rainTop(int x, int z) {
            for (int y = 120; y > 64; y--) {
                if (block(x, y, z) != Block.OPEN) {
                    return y + 1;
                }
            }
            return 65;
        }

        @Override
        public Block block(int x, int y, int z) {
            Block block = blocks.get(key(x, y, z));
            return block != null ? block : y <= 64 ? Block.SOLID : Block.OPEN;
        }

        @Override
        public RainDetailRules.StreakFace streakFace(int x, int y, int z, int dx, int dz) {
            boolean[] sides = panes.get(key(x, y, z));
            if (sides != null) {
                return RainDetailRules.paneFace(sides[0], sides[1], sides[2], sides[3], dx, dz);
            }
            return faces.get(key(x, y, z));
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF);
        }
    }

    /** Plank walls around x and z 0 to 4 from 65 to 67, and a roof at 68 reaching one block past every wall. */
    private static TestWorld houseWithEaves() {
        TestWorld world = new TestWorld();
        world.fill(0, 65, 0, 4, 67, 0, Block.SOLID, false);
        world.fill(0, 65, 4, 4, 67, 4, Block.SOLID, false);
        world.fill(0, 65, 0, 0, 67, 4, Block.SOLID, false);
        world.fill(4, 65, 0, 4, 67, 4, Block.SOLID, false);
        world.fill(-1, 68, -1, 5, 68, 5, Block.SOLID, false);
        return world;
    }

    @Test
    void dripsFallFromEaves() {
        RainDetailRules.DripSpot spot = RainDetailRules.dripSpot(houseWithEaves(), -1, 2);
        assertNotNull(spot);
        assertEquals(68.0, spot.underside());
        assertEquals(-1, spot.towardX(), "toward the open side, away from the house");
        assertTrue(!spot.leaves());
    }

    @Test
    void nothingDripsInsideUnderTheMiddleOfARoof() {
        assertNull(RainDetailRules.dripSpot(houseWithEaves(), 2, 2));
    }

    @Test
    void aRoofFlushWithItsWallDoesNotDrip() {
        TestWorld world = new TestWorld();
        world.fill(0, 65, 0, 0, 67, 4, Block.SOLID, false);
        world.fill(0, 68, 0, 4, 68, 4, Block.SOLID, false);
        assertNull(RainDetailRules.dripSpot(world, 0, 2), "the wall is under the edge: water runs down it instead");
    }

    @Test
    void dripsFallFromTheLowestLeavesWithAirBelow() {
        TestWorld world = new TestWorld();
        world.fill(0, 65, 0, 0, 69, 0, Block.SOLID, false);
        world.fill(1, 68, 0, 1, 71, 0, Block.LEAVES, false);
        RainDetailRules.DripSpot spot = RainDetailRules.dripSpot(world, 1, 0);
        assertNotNull(spot);
        assertTrue(spot.leaves());
        assertEquals(68.0, spot.underside());
        assertNull(RainDetailRules.dripSpot(world, 0, 0), "the trunk's top has no air under it");
    }

    @Test
    void openGroundDoesNotDrip() {
        assertNull(RainDetailRules.dripSpot(new TestWorld(), 10, 10));
    }

    @Test
    void streaksRunOnHardWallsAboveTheGroundInFront() {
        TestWorld world = new TestWorld();
        world.fill(1, 65, 0, 1, 69, 0, Block.SOLID, true);
        int[] range = RainDetailRules.wallRange(world, 0, 0, 1, 0, 0, 200);
        assertArrayEquals(new int[] {65, 69}, range);
        assertEquals(RainDetailRules.StreakFace.HARD_WALL, RainDetailRules.streakFace(world, 0, 0, 1, 0, 67));
        assertArrayEquals(new int[] {66, 68}, RainDetailRules.wallRange(world, 0, 0, 1, 0, 66, 68), "clipped to the height asked");
    }

    @Test
    void noStreaksOnSoftWallsLowStepsOrUnderCover() {
        TestWorld planks = new TestWorld();
        planks.fill(1, 65, 0, 1, 69, 0, Block.SOLID, false);
        assertNull(RainDetailRules.streakFace(planks, 0, 0, 1, 0, 67), "planks aren't hard");

        TestWorld step = new TestWorld();
        step.put(1, 65, 0, Block.SOLID, true);
        assertNull(RainDetailRules.wallRange(step, 0, 0, 1, 0, 0, 200), "a single step is no wall");

        TestWorld covered = new TestWorld();
        covered.fill(1, 65, 0, 1, 69, 0, Block.SOLID, true);
        covered.put(0, 70, 0, Block.SOLID, false);
        assertNull(RainDetailRules.wallRange(covered, 0, 0, 1, 0, 0, 200), "a roof over the air in front keeps the rain off");
    }

    @Test
    void streaksRunDownBothFacesOfFreeStandingGlass() {
        TestWorld world = new TestWorld();
        world.glass(1, 65, 0, 1, 67, 0);
        assertArrayEquals(new int[] {65, 67}, RainDetailRules.wallRange(world, 0, 0, 1, 0, 0, 200));
        RainDetailRules.StreakFace west = RainDetailRules.streakFace(world, 0, 0, 1, 0, 66);
        RainDetailRules.StreakFace east = RainDetailRules.streakFace(world, 2, 0, -1, 0, 66);
        assertEquals(RainDetailRules.StreakFace.GLASS_BLOCK, west);
        assertEquals(RainDetailRules.StreakFace.GLASS_BLOCK, east, "the far side is rained on as well");
        assertTrue(west.glass());
        assertEquals(0.0, west.inset());
    }

    @Test
    void aWindowUnderItsRoofStaysDryInside() {
        TestWorld world = new TestWorld();
        // A glass wall along x = 1 with a roof over the inside (x 2 to 4) at 68.
        world.glass(1, 65, 0, 1, 67, 0);
        world.fill(1, 68, 0, 4, 68, 0, Block.SOLID, false);
        assertNotNull(RainDetailRules.streakFace(world, 0, 0, 1, 0, 66), "the outside is open to the rain");
        assertNull(RainDetailRules.wallRange(world, 2, 0, -1, 0, 0, 200), "the inside is under the roof");
    }

    @Test
    void panesTakeStreaksOnTheirCentredFaces() {
        TestWorld world = new TestWorld();
        // A pane wall running north to south along x = 1, three high.
        for (int y = 65; y <= 67; y++) {
            world.pane(1, y, 0, true, false, true, false);
        }
        RainDetailRules.StreakFace face = RainDetailRules.streakFace(world, 0, 0, 1, 0, 66);
        assertNotNull(face);
        assertTrue(face.glass());
        assertEquals(7.0 / 16.0, face.inset(), 1.0e-9, "a pane's face lies in the middle of its block");
        assertEquals(0.0, face.from());
        assertEquals(1.0, face.to());
        assertNotNull(RainDetailRules.streakFace(world, 2, 0, -1, 0, 66), "and on its other face");
    }

    @Test
    void paneFacesSpanTheirArmsAndSkipEndsAndArmsInTheWay() {
        RainDetailRules.StreakFace northOnly = RainDetailRules.paneFace(true, false, false, false, -1, 0);
        assertEquals(0.0, northOnly.from());
        assertEquals(9.0 / 16.0, northOnly.to(), 1.0e-9, "from the north edge to the far side of the post");
        RainDetailRules.StreakFace eastOnly = RainDetailRules.paneFace(false, true, false, false, 0, 1);
        assertEquals(7.0 / 16.0, eastOnly.from(), 1.0e-9);
        assertEquals(1.0, eastOnly.to());
        assertNull(RainDetailRules.paneFace(false, true, false, true, -1, 0), "the narrow end of a pane running east to west");
        assertNull(RainDetailRules.paneFace(false, false, false, false, 1, 0), "a lone post");
        assertNull(RainDetailRules.paneFace(true, true, true, false, 1, 0), "an arm sticks out of the face toward the rain");
        assertNotNull(RainDetailRules.paneFace(true, true, true, false, -1, 0), "the face on the other side is clear");
    }

    @Test
    void windDrivesRainOntoTheFacesTurnedIntoIt() {
        // Wind blowing east: faces looking west are hit, faces looking east are sheltered.
        assertEquals(1.0f, RainDetailRules.windShare(-1, 0, 0.2f, 0.0f), 1.0e-6f);
        assertEquals(0.33f, RainDetailRules.windShare(1, 0, 0.2f, 0.0f), 1.0e-6f);
        assertEquals(0.33f, RainDetailRules.windShare(0, 1, 0.2f, 0.0f), 1.0e-6f, "side faces only get the base share");
        assertEquals(0.66f, RainDetailRules.windShare(1, 0, 0.0f, 0.0f), 1.0e-6f, "no wind: every face alike");
    }
}
