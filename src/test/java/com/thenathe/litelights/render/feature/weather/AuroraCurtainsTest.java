package com.thenathe.litelights.render.feature.weather;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuroraCurtainsTest {
    private static final long SHAPE = 0x5EED_A0A0L;

    private record Vertex(float x, float y, float z, float u, float v, int rgb, float alpha) {
        double elevation() {
            return Math.toDegrees(Math.atan2(y, Math.hypot(x, z)));
        }

        /** Radians from north (-z), turning east (+x). */
        double bearing() {
            return Math.atan2(x, -z);
        }
    }

    private static List<Vertex> build(float strength, float bearing, float seconds) {
        List<Vertex> vertices = new ArrayList<>();
        int quads = new AuroraCurtains().build(SHAPE, strength, strength, bearing, seconds,
            (x, y, z, u, v, rgb, alpha) -> vertices.add(new Vertex(x, y, z, u, v, rgb, alpha)));
        assertEquals(quads * 4, vertices.size());
        return vertices;
    }

    @Test
    void strongNightsHaveMoreBands() {
        assertEquals(3, AuroraCurtains.bandCount(0.45f));
        assertEquals(4, AuroraCurtains.bandCount(0.7f));
        assertEquals(5, AuroraCurtains.bandCount(1.0f));
        assertTrue(build(1.0f, 0.0f, 10.0f).size() > build(0.45f, 0.0f, 10.0f).size());
        assertTrue(build(1.0f, 0.0f, 10.0f).size() / 4 <= AuroraCurtains.MAX_QUADS);
    }

    @Test
    void everyVertexSitsInsideTheSkyAndTheFarPlane() {
        for (float seconds : new float[] {0.0f, 37.0f, 900.0f, 3599.0f}) {
            for (Vertex vertex : build(1.0f, 0.0f, seconds)) {
                double distance = Math.sqrt(vertex.x * vertex.x + vertex.y * vertex.y + vertex.z * vertex.z);
                assertTrue(distance > 2.0 && distance < 120.0, "at " + distance + " blocks");
                assertTrue(vertex.alpha >= 0.0f && vertex.alpha <= 1.5f);
            }
        }
    }

    @Test
    void curtainsStandTallInTheNorthernSky() {
        List<Vertex> vertices = build(1.0f, 0.0f, 20.0f);
        double highestTop = 0.0;
        double north = 0.0;
        double weight = 0.0;
        for (Vertex vertex : vertices) {
            if (vertex.alpha < 0.05f) {
                continue;
            }
            if (vertex.v == 1.0f) {
                highestTop = Math.max(highestTop, vertex.elevation());
            }
            north += Math.cos(vertex.bearing()) * vertex.alpha;
            weight += vertex.alpha;
        }
        assertTrue(highestTop > 60.0, "a strong night's curtains reach high: " + highestTop);
        assertTrue(north / weight > 0.3, "the light is mostly in the north: " + north / weight);
    }

    @Test
    void aBearingTurnsTheWholeAurora() {
        List<Vertex> north = build(0.85f, 0.0f, 5.0f);
        List<Vertex> east = build(0.85f, (float) (Math.PI / 2.0), 5.0f);
        assertEquals(north.size(), east.size());
        for (int i = 0; i < north.size(); i++) {
            Vertex a = north.get(i);
            Vertex b = east.get(i);
            // A quarter turn east: north (-z) becomes east (+x).
            assertEquals(-a.z, b.x, 1.0e-3);
            assertEquals(a.x, b.z, 1.0e-3);
            assertEquals(a.y, b.y, 1.0e-4);
        }
    }

    @Test
    void theFoldsMoveSlowly() {
        List<Vertex> now = build(0.85f, 0.0f, 100.0f);
        List<Vertex> later = build(0.85f, 0.0f, 100.05f);
        double largest = 0.0;
        for (int i = 0; i < Math.min(now.size(), later.size()); i++) {
            Vertex a = now.get(i);
            Vertex b = later.get(i);
            largest = Math.max(largest, Math.abs(a.x - b.x) + Math.abs(a.z - b.z));
        }
        assertTrue(largest > 0.0 && largest < 0.05, "a frame apart, nothing jumps: " + largest);
    }

    @Test
    void sheetsSeenEdgeOnAreBrighter() {
        assertEquals(1.0f, AuroraCurtains.edgeOnBrightening(1.0f, 0.0f, 0.0f, 1.0f), 1.0e-5);
        assertEquals(AuroraCurtains.MAX_EDGE_ON, AuroraCurtains.edgeOnBrightening(1.0f, 0.0f, 1.0f, 0.0f), 1.0e-5);
        float slanted = AuroraCurtains.edgeOnBrightening(1.0f, 0.0f, 1.0f, 1.0f);
        assertTrue(slanted > 1.3f && slanted < 1.5f, "at 45 degrees, about the square root of two: " + slanted);
    }

    @Test
    void theHorizonDimsAndHidesTheEnds() {
        assertEquals(0.0f, AuroraCurtains.horizonFade(-50.0f, 1000.0f), 1.0e-6);
        assertTrue(AuroraCurtains.horizonFade(30.0f, 1000.0f) < 0.6f);
        assertEquals(1.0f, AuroraCurtains.horizonFade(300.0f, 300.0f), 1.0e-6);
    }

    @Test
    void everyNightHasAMagentaFringeAndStrongNightsAStrongerOne() {
        float weak = 0.0f;
        float strong = 0.0f;
        for (Vertex vertex : build(0.45f, 0.0f, 0.0f)) {
            weak = Math.max(weak, (vertex.rgb >> 16 & 0xFF) / 255.0f);
        }
        for (Vertex vertex : build(1.0f, 0.0f, 0.0f)) {
            strong = Math.max(strong, (vertex.rgb >> 16 & 0xFF) / 255.0f);
        }
        assertTrue(weak > 0.4f, "a fringe on a quiet night: " + weak);
        assertTrue(strong > weak);
    }
}
