package com.thenathe.litelights.clouds.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The face format packs and unpacks every field, at its ends too, as the shader reads it. */
class FaceFormatTest {
    @Test
    void positionsRoundTripAtTheirEnds() {
        int[] offsets = {-FaceFormat.MOST_OFFSET, -1, 0, 1, 300, FaceFormat.MOST_OFFSET};
        for (int x : offsets) {
            for (int z : offsets) {
                for (int y : new int[] {0, 1, 127, FaceFormat.MOST_HEIGHT_CELLS}) {
                    for (int side = 0; side < 6; side++) {
                        int position = FaceFormat.position(x, z, y, side);
                        assertEquals(x, FaceFormat.x(position));
                        assertEquals(z, FaceFormat.z(position));
                        assertEquals(y, FaceFormat.y(position));
                        assertEquals(side, FaceFormat.side(position));
                    }
                }
            }
        }
    }

    @Test
    void looksRoundTrip() {
        for (int state : new int[] {FaceFormat.STABLE, FaceFormat.APPEARING, FaceFormat.DISAPPEARING}) {
            for (int from = -1; from < 6; from++) {
                int look = FaceFormat.look(2, state, from, from % 2 == 0, 0.25f, 0.75f, 15, FaceFormat.FADE_OUT);
                assertEquals(2, FaceFormat.sizeShift(look));
                assertEquals(state, FaceFormat.state(look));
                assertEquals(from, FaceFormat.growFrom(look));
                assertEquals(from % 2 == 0, FaceFormat.widens(look));
                assertEquals(0.25f, FaceFormat.windowStart(look), 0.5f / FaceFormat.WINDOW_STEPS);
                assertEquals(0.75f, FaceFormat.windowEnd(look), 0.5f / FaceFormat.WINDOW_STEPS);
                assertEquals(15, FaceFormat.storm(look));
                assertEquals(FaceFormat.FADE_OUT, FaceFormat.fade(look));
                assertEquals(FaceFormat.FADE_IN, FaceFormat.fade(FaceFormat.faded(look, FaceFormat.FADE_IN)));
                assertEquals(FaceFormat.storm(look), FaceFormat.storm(FaceFormat.faded(look, FaceFormat.FADE_IN)));
            }
        }
    }

    @Test
    void windowsKeepTheirOrderAndShadesAreClamped() {
        int look = FaceFormat.look(0, FaceFormat.APPEARING, FaceFormat.DOWN, false, 0.7f, 0.2f, 40, FaceFormat.FADE_NONE);
        assertTrue(FaceFormat.windowEnd(look) >= FaceFormat.windowStart(look), "a window never ends before it starts");
        assertEquals(FaceFormat.MOST_SHADE, FaceFormat.storm(look));
    }

    @Test
    void shadingAndMovesRoundTrip() {
        int[] normals = {FaceFormat.normal(FaceFormat.UP, 0, 1, 0), FaceFormat.normal(FaceFormat.UP, 1, 1, 0),
            FaceFormat.normal(FaceFormat.UP, 0, 1, -1), FaceFormat.normal(FaceFormat.UP, -1, 2, 3)};
        for (int cells = 1; cells <= FaceFormat.MOST_STACK; cells++) {
            int word2 = FaceFormat.shadeWord2(normals);
            int word3 = FaceFormat.shadeWord3(normals, 37.0f, 1_404.0f, cells);
            for (int corner = 0; corner < 4; corner++) {
                assertEquals(normals[corner], FaceFormat.cornerNormal(word2, word3, corner));
            }
            assertEquals(37.0f, FaceFormat.edgeTop(word3, 0), 1.01f);
            assertEquals(1_404.0f, FaceFormat.edgeTop(word3, 1), 1.01f);
            assertEquals(cells, FaceFormat.cells(word3));
        }
        assertEquals(FaceFormat.MOST_TOP, FaceFormat.edgeTop(FaceFormat.shadeWord3(normals, 9_000.0f, 0.0f, 1), 0), 0.01f);
        int position = FaceFormat.position(31, 0, 40, FaceFormat.EAST);
        int moved = FaceFormat.moved(position, -100, 77);
        assertEquals(-69, FaceFormat.x(moved));
        assertEquals(77, FaceFormat.z(moved));
        assertEquals(40, FaceFormat.y(moved));
        assertEquals(FaceFormat.EAST, FaceFormat.side(moved));
    }

    @Test
    void normalsRoundTripWithinAFewDegrees() {
        java.util.Random random = new java.util.Random(5);
        for (int side = 0; side < 6; side++) {
            for (int i = 0; i < 2_000; i++) {
                double x = random.nextGaussian();
                double y = random.nextGaussian();
                double z = random.nextGaussian();
                double length = Math.sqrt(x * x + y * y + z * z);
                if ((x * CloudMesher.dx(side) + y * CloudMesher.dy(side) + z * CloudMesher.dz(side)) / length < 0.05) {
                    continue;
                }
                float[] back = FaceFormat.direction(side, FaceFormat.normal(side, x, y, z));
                double cosine = (back[0] * x + back[1] * y + back[2] * z) / length;
                assertTrue(cosine > Math.cos(Math.toRadians(5.0)), "off by " + Math.toDegrees(Math.acos(Math.min(1.0, cosine))) + " degrees");
            }
            float[] axis = FaceFormat.direction(side, FaceFormat.normal(side, CloudMesher.dx(side), CloudMesher.dy(side), CloudMesher.dz(side)));
            assertEquals(CloudMesher.dx(side), axis[0], 0.001f);
            assertEquals(CloudMesher.dy(side), axis[1], 0.001f);
            assertEquals(CloudMesher.dz(side), axis[2], 0.001f);
        }
    }

    @Test
    void facesOutOfReachAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> FaceFormat.position(FaceFormat.MOST_OFFSET + 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FaceFormat.position(0, -FaceFormat.MOST_OFFSET - 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FaceFormat.position(0, 0, FaceFormat.MOST_HEIGHT_CELLS + 1, 0));
    }
}
