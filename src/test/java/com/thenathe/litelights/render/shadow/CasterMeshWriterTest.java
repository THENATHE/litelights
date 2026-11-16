package com.thenathe.litelights.render.shadow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasterMeshWriterTest {
    private static CasterMesh quad(float x, float uvOffset) {
        CasterMeshWriter writer = new CasterMeshWriter();
        writer.vertex(x, 0, 0, uvOffset, 0, 0);
        writer.vertex(x + 1, 0, 0, uvOffset, 0, 0);
        writer.vertex(x + 1, 1, 0, uvOffset, 0, 0);
        writer.vertex(x, 1, 0, uvOffset, 0, 0);
        writer.endQuad();
        return writer.finish(42L);
    }

    @Test
    void sameGeometryHashesTheSame() {
        assertEquals(quad(3, 0.25f).hash(), quad(3, 0.25f).hash());
    }

    @Test
    void differentGeometryHashesDifferently() {
        assertNotEquals(quad(3, 0.25f).hash(), quad(4, 0.25f).hash());
        assertNotEquals(quad(3, 0.25f).hash(), quad(3, 0.5f).hash());
    }

    @Test
    void positionsRoundTripAtOneSixtyFourthOfABlock() {
        for (float blocks : new float[] {-8f, -0.5f, 0f, 1f / 16, 0.3125f, 15.9375f, 16f, 40f}) {
            int encoded = CasterMeshWriter.encodePosition(blocks);
            float decoded = encoded / (float) CasterMesh.POSITION_SCALE - CasterMesh.POSITION_OFFSET;
            assertTrue(Math.abs(decoded - blocks) <= 0.5f / CasterMesh.POSITION_SCALE, "position " + blocks);
        }
    }

    @Test
    void emptyWriterGivesAnEmptyMesh() {
        CasterMesh mesh = new CasterMeshWriter().finish(1L);
        assertTrue(mesh.isEmpty());
        assertEquals(0L, mesh.hash());
    }
}
