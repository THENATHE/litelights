package com.thenathe.litelights.render.feature.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drifting foam's pattern (include/water/surface.glsl, lite_water_drifting_noise, with the value
 * noise of include/water/common.glsl), mirrored here: still water keeps the stationary pattern, the
 * pattern never jumps as its two copies take turns, and it travels downstream.
 */
class DriftingFoamTest {
    private static final float SPEED = 0.6f;
    private static final float CYCLE = 4.0f;

    // --- Mirror of the shader ---

    private static int hashBits(int x, int y) {
        int vx = x & 1023;
        int vy = y & 1023;
        int h = vx * 0x8da6b343 ^ vy * 0xd8163841 ^ 0x9e3779b9;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        h *= 0x297a2d39;
        h ^= h >>> 15;
        return h;
    }

    private static float hash(int x, int y) {
        return (hashBits(x, y) & 0xFFFFFF) / 16777216.0f;
    }

    static float valueNoise(float px, float py) {
        int cellX = (int) Math.floor(px);
        int cellY = (int) Math.floor(py);
        float fx = px - cellX;
        float fy = py - cellY;
        float sx = fx * fx * (3 - 2 * fx);
        float sy = fy * fy * (3 - 2 * fy);
        float a = hash(cellX, cellY);
        float b = hash(cellX + 1, cellY);
        float c = hash(cellX, cellY + 1);
        float d = hash(cellX + 1, cellY + 1);
        float bottom = a + (b - a) * sx;
        float top = c + (d - c) * sx;
        return bottom + (top - bottom) * sy;
    }

    static float driftingNoise(float px, float py, float scale, float flowX, float flowZ, float time) {
        return driftingNoise(px, py, 0, 0, 0, scale, flowX, flowZ, time);
    }

    static float driftingNoise(float px, float py, float ownX, float ownY, float keepOnFlow, float scale, float flowX, float flowZ,
                               float time) {
        float flowing = Math.min(1, (float) Math.hypot(flowX, flowZ) * 8);
        float keep = 1 + (keepOnFlow - 1) * flowing;
        px += ownX * keep;
        py += ownY * keep;
        float phase = fract(time / CYCLE);
        float otherPhase = fract(phase + 0.5f);
        float travelX = flowX * SPEED * CYCLE * scale;
        float travelZ = flowZ * SPEED * CYCLE * scale;
        float first = valueNoise(px - travelX * phase, py - travelZ * phase);
        float second = valueNoise(px - travelX * otherPhase, py - travelZ * otherPhase);
        float weight = 1 - Math.abs(2 * phase - 1);
        float blended = second + (first - second) * weight;
        float flattening = (float) (1 / Math.sqrt(weight * weight + (1 - weight) * (1 - weight)));
        return Math.clamp(0.5f + (blended - 0.5f) * (1 + (flattening - 1) * flowing), 0, 1);
    }

    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }

    // --- Tests ---

    @Test
    void stillWaterKeepsTheStationaryPattern() {
        for (int i = 0; i < 200; i++) {
            float x = i * 0.37f;
            float y = i * 0.61f - 40;
            float time = i * 0.173f;
            // The shore foam's own drift, as Stationary moves it.
            float ownX = time * 0.35f;
            float ownY = -time * 0.2f;
            assertEquals(valueNoise(x + ownX, y + ownY), driftingNoise(x, y, ownX, ownY, 0, 4, 0, 0, time), 1e-5);
        }
    }

    @Test
    void onFlowingWaterThePatternsOwnMotionGivesWayToTheCurrent() {
        // With the own motion fully replaced (keep 0), adding any own motion changes nothing at full flow.
        for (int i = 0; i < 50; i++) {
            float time = 1.1f + i * 0.13f;
            assertEquals(driftingNoise(i * 0.3f, 2, 0, 0, 0, 4, 1, 0, time),
                driftingNoise(i * 0.3f, 2, time * 0.35f, -time * 0.2f, 0, 4, 1, 0, time), 1e-6);
        }
    }

    @Test
    void thePatternNeverJumpsWhenACopyStartsOver() {
        // Either side of the moments a copy starts over (every half cycle), at full flow.
        for (float restart = 0; restart < 30; restart += CYCLE / 2) {
            for (int i = 0; i < 50; i++) {
                float x = i * 0.29f;
                float y = 7 - i * 0.43f;
                float before = driftingNoise(x, y, 4, 1, 0, restart + CYCLE / 2 - 1e-3f);
                float after = driftingNoise(x, y, 4, 1, 0, restart + CYCLE / 2 + 1e-3f);
                assertEquals(before, after, 0.02, "at " + restart);
            }
        }
    }

    @Test
    void neighbouringPointsWithNearlyTheSameFlowAgree() {
        // The flow is blended between column centres, so across a block border (here a step of
        // 0.002 of a block where the flow changes by a whole unit per block) it changes by a hair,
        // and so does the pattern.
        for (int i = 0; i < 100; i++) {
            float time = 0.4f + i * 0.31f;
            float x = 16 * 4.0f;
            float left = driftingNoise(x - 4e-3f, 3, 4, 0.700f, 0.30f, time);
            float right = driftingNoise(x + 4e-3f, 3, 4, 0.702f, 0.30f, time);
            assertEquals(left, right, 0.03);
        }
    }

    @Test
    void thePatternTravelsDownstream() {
        // Flow along +x: the pattern a moment later should match the pattern a little upstream now,
        // better than it matches the pattern where it was (or downstream). Checked over many points.
        float flow = 1.0f;
        float scale = 4.0f;
        float dt = 0.2f;
        float shift = flow * SPEED * dt * scale;
        double upstream = 0;
        double inPlace = 0;
        double downstream = 0;
        for (int i = 0; i < 2000; i++) {
            float x = (i % 50) * 0.53f;
            float y = (i / 50) * 0.47f;
            float time = 0.75f + (i % 7) * 0.05f;
            float later = driftingNoise(x + shift, y, scale, flow, 0, time + dt);
            upstream += Math.abs(later - driftingNoise(x, y, scale, flow, 0, time));
            inPlace += Math.abs(later - driftingNoise(x + shift, y, scale, flow, 0, time));
            downstream += Math.abs(later - driftingNoise(x + 2 * shift, y, scale, flow, 0, time));
        }
        assertTrue(upstream < inPlace * 0.6, "upstream " + upstream + ", in place " + inPlace);
        assertTrue(upstream < downstream * 0.6, "upstream " + upstream + ", downstream " + downstream);
    }

    @Test
    void driftingFoamKeepsTheNoiseContrast() {
        // Two copies blended would flatten the noise toward 0.5; the shader undoes that.
        double still = 0;
        double drifting = 0;
        int samples = 4000;
        for (int i = 0; i < samples; i++) {
            float x = (i % 80) * 0.41f;
            float y = (i / 80) * 0.37f;
            still += Math.abs(valueNoise(x, y) - 0.5);
            drifting += Math.abs(driftingNoise(x, y, 4, 1, 0, CYCLE * 0.25f) - 0.5);
        }
        assertEquals(1.0, drifting / still, 0.15);
    }
}
