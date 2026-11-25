package com.thenathe.litelights.render.feature.weather;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which faces of glass frost, snow and sand reach (GlassShaderMirror, the shader's rules): a house on flat
 * ground whose top is at y 64, so rain stops at 65 outside. The house covers x and z 0 to 4, its
 * walls rise from 65 to 68 and its roof is at 69, so rain stops at 70 over it. Windows are in the
 * west wall (x 0); the room inside is three blocks deep (x 1 to 3), with the east wall at x 4.
 */
class GlassExposureTest {
    private static final int[] WEST = {-1, 0, 0};
    private static final int[] EAST = {1, 0, 0};
    private static final int[] UP = {0, 1, 0};
    private static final int[] DOWN = {0, -1, 0};

    /** The house, its roof reaching {@code overhang} blocks out past the west wall. */
    private static GlassShaderMirror house(int overhang) {
        return new GlassShaderMirror((x, z) -> x >= -overhang && x <= 4 && z >= 0 && z <= 4 ? 70 : 65);
    }

    private static final double[] OUTER_FACE = {0.0, 66.5, 2.5};
    private static final double[] INNER_FACE = {1.0, 66.5, 2.5};

    @Test
    void theOutsideOfAWindowIsRainedOn() {
        GlassShaderMirror world = house(0);
        assertArrayEquals(new int[] {-1, 66, 2}, GlassShaderMirror.front(OUTER_FACE, WEST), "the face looks into the air just west of the wall");
        assertArrayEquals(new int[] {0, 66, 2}, GlassShaderMirror.glass(OUTER_FACE, WEST));
        assertEquals(1.0f, world.exposure(OUTER_FACE, WEST));
    }

    @Test
    void theInsideOfAWindowStaysDry() {
        assertEquals(0.0f, house(0).exposure(INNER_FACE, EAST));
    }

    @Test
    void fromInsideTheSnowOnTheOutsideShowsThrough() {
        GlassShaderMirror world = house(0);
        double[] lookingWest = {-1.0, 0.0, 0.0};
        assertEquals(0.9f, world.seenExposure(INNER_FACE, EAST, lookingWest), 1.0e-6f);
        double s = Math.sqrt(0.5);
        double[] lookingWestAndDown = {-s, -s, 0.0};
        assertEquals(0.9f, world.seenExposure(INNER_FACE, EAST, lookingWestAndDown), 1.0e-6f,
            "at an angle the far face is still found, a block further along the line of sight");
    }

    @Test
    void overhangsAndPorchesStillCountAsOutdoors() {
        assertEquals(0.5f, house(1).exposure(OUTER_FACE, WEST), "one block of eave");
        assertEquals(0.35f, house(2).exposure(OUTER_FACE, WEST), "two blocks of eave");
        assertEquals(0.25f, house(3).exposure(OUTER_FACE, WEST), "a porch roof three blocks deep");
        assertEquals(0.0f, house(4).exposure(OUTER_FACE, WEST), "four blocks of roof: too deep to count");
        for (int overhang = 0; overhang <= 3; overhang++) {
            assertEquals(0.0f, house(overhang).exposure(INNER_FACE, EAST), "the room stays indoors under a " + overhang + "-block eave");
        }
    }

    @Test
    void aNarrowRoomIsIndoorsThoughTheOutdoorsIsJustBeyondItsBackWall() {
        // A room one block deep (x 1) with its back wall at x 2: rain falls two blocks out from the
        // window's inside, past the back wall, but right in front of its outside.
        GlassShaderMirror narrow = new GlassShaderMirror((x, z) -> x >= 0 && x <= 2 && z >= 0 && z <= 4 ? 70 : 65);
        assertEquals(1.0f, narrow.exposure(OUTER_FACE, WEST));
        assertEquals(0.0f, narrow.exposure(INNER_FACE, EAST));
        // Under a one-block eave as well, the outside is still the nearer to the sky.
        GlassShaderMirror eave = new GlassShaderMirror((x, z) -> x >= -1 && x <= 2 && z >= 0 && z <= 4 ? 70 : 65);
        assertEquals(0.5f, eave.exposure(OUTER_FACE, WEST));
        assertEquals(0.0f, eave.exposure(INNER_FACE, EAST));
    }

    @Test
    void aGlassHouseIsDryInsideThoughLightComesThrough() {
        // Glass blocks motion, so a glass roof stops the rain as a plank one does, even though it lets
        // the full sky light through (why exposure is not judged by sky light).
        GlassShaderMirror glassHouse = house(0);
        assertEquals(0.0f, glassHouse.exposure(new double[] {1.0, 67.5, 1.5}, EAST));
        double[] roofTop = {2.5, 70.0, 2.5};
        assertArrayEquals(new int[] {2, 70, 2}, GlassShaderMirror.front(roofTop, UP));
        assertEquals(1.0f, glassHouse.exposure(roofTop, UP), "the roof's top is rained on");
        double[] roofUnderside = {2.5, 69.0, 2.5};
        assertEquals(0.0f, glassHouse.exposure(roofUnderside, DOWN), "faces looking down never are");
    }

    @Test
    void lookingUpThroughAGlassRoofShowsTheSnowOnIt() {
        GlassShaderMirror glassHouse = house(0);
        double[] roofUnderside = {2.5, 69.0, 2.5};
        double[] up = {0.0, 1.0, 0.0};
        assertEquals(0.9f, glassHouse.seenExposure(roofUnderside, DOWN, up), 1.0e-6f);
    }

    @Test
    void paneFacesLookIntoTheBlocksBesideThePane() {
        // A pane's faces lie inside its block, at 7/16 and 9/16.
        double[] westFace = {7.0 / 16.0, 66.5, 2.5};
        double[] eastFace = {9.0 / 16.0, 66.5, 2.5};
        assertArrayEquals(new int[] {-1, 66, 2}, GlassShaderMirror.front(westFace, WEST));
        assertArrayEquals(new int[] {1, 66, 2}, GlassShaderMirror.front(eastFace, EAST));
        assertArrayEquals(new int[] {0, 66, 2}, GlassShaderMirror.glass(eastFace, EAST));
        GlassShaderMirror world = house(0);
        assertEquals(1.0f, world.exposure(westFace, WEST));
        assertEquals(0.0f, world.exposure(eastFace, EAST), "a pane window's inside is indoors too");
        double[] lookingWest = {-1.0, 0.0, 0.0};
        assertEquals(0.9f, world.seenExposure(eastFace, EAST, lookingWest), 1.0e-6f,
            "from inside, a pane's outside is an eighth of a block away");
    }

    @Test
    void freeStandingGlassIsOutdoorsOnBothSides() {
        GlassShaderMirror open = new GlassShaderMirror((x, z) -> 65);
        double[] westFace = {7.0 / 16.0, 66.5, 2.5};
        double[] eastFace = {9.0 / 16.0, 66.5, 2.5};
        assertEquals(1.0f, open.exposure(westFace, WEST));
        assertEquals(1.0f, open.exposure(eastFace, EAST));
        assertEquals(1.0f, open.exposure(OUTER_FACE, WEST), "a glass block's west face");
        assertEquals(1.0f, open.exposure(INNER_FACE, EAST), "and its east face");
    }

    @Test
    void glassUnderAWalkwayRoofIsOutdoorsOnBothSides() {
        // A roof over x -1 to 1, open at both ends: a glass block at x 0 under it.
        GlassShaderMirror walkway = new GlassShaderMirror((x, z) -> x >= -1 && x <= 1 ? 70 : 65);
        assertEquals(0.5f, walkway.exposure(OUTER_FACE, WEST));
        assertEquals(0.5f, walkway.exposure(INNER_FACE, EAST));
    }

    @Test
    void frostFormsOnlyOnTheOutdoorSideAsSnowDoes() {
        GlassShaderMirror world = house(0);
        assertEquals(1.0f, world.frost(OUTER_FACE, WEST));
        assertEquals(0.0f, world.frost(INNER_FACE, EAST), "no frost of its own on the inside of a room");
        double[] lookingWest = {-1.0, 0.0, 0.0};
        assertEquals(0.9f, world.seenFrost(INNER_FACE, EAST, lookingWest), 1.0e-6f,
            "from inside, the frost on the outside shows through once");
        for (int overhang = 0; overhang <= 4; overhang++) {
            GlassShaderMirror eave = house(overhang);
            assertEquals(eave.exposure(OUTER_FACE, WEST), eave.frost(OUTER_FACE, WEST), "under a " + overhang + "-block eave");
            assertEquals(0.0f, eave.frost(INNER_FACE, EAST));
        }
        GlassShaderMirror open = new GlassShaderMirror((x, z) -> 65);
        assertEquals(1.0f, open.frost(OUTER_FACE, WEST), "free-standing glass frosts on both sides");
        assertEquals(1.0f, open.frost(INNER_FACE, EAST));
    }

    @Test
    void glassWithWaterAgainstItDoesNotFrost() {
        // A room's window (the room under a roof from x 1 to 5) with the last block of a stream of
        // water against its west face (x -1, surface at 67) and open air beyond: rain stops at the
        // water's surface over x -1, so the face's open sky is a block out and it gets half the snow.
        // It gets no frost.
        GlassShaderMirror stream = new GlassShaderMirror((x, z) -> x == -1 ? 67 : x >= 1 && x <= 5 ? 70 : 65,
            (x, z) -> x == -1 ? 67 : GlassShaderMirror.NO_TOP);
        double[] lowFace = {0.0, 66.5, 2.5};
        assertTrue(stream.underFluid(GlassShaderMirror.front(lowFace, WEST)));
        assertEquals(0.5f, stream.exposure(lowFace, WEST));
        assertEquals(0.0f, stream.frost(lowFace, WEST));
        assertEquals(0.0f, stream.seenFrost(new double[] {1.0, 66.5, 2.5}, EAST, new double[] {-1.0, 0.0, 0.0}),
            "nor is any seen through the glass from the other side");
        double[] aboveTheWater = {0.0, 67.5, 2.5};
        assertEquals(1.0f, stream.frost(aboveTheWater, WEST), "the glass above the water frosts");
    }

    @Test
    void unknownColumnsAreTreatedAsDry() {
        GlassShaderMirror unknown = new GlassShaderMirror((x, z) -> GlassShaderMirror.NO_TOP);
        assertFalse(unknown.open(0, 100, 0));
    }

    @Test
    void snowPilesOnlyWhereTheGroundOrALedgeIsRightBelow() {
        GlassShaderMirror world = house(0);
        assertTrue(world.onLedge(GlassShaderMirror.front(new double[] {0.0, 65.5, 2.5}, WEST)),
            "a window whose bottom row sits on the ground");
        assertFalse(world.onLedge(GlassShaderMirror.front(new double[] {0.0, 66.5, 2.5}, WEST)),
            "a window a block up, over open air");
    }
}
