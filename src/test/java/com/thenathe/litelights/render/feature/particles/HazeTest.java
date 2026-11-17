package com.thenathe.litelights.render.feature.particles;

import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The haze puffs, Mist field and their helpers (fog097): the puff shader's exact integral (mirrored
 * here from particle/puff.fsh), how a puff is packed into vanilla's vertex format, where puffs
 * gather, how the noise drifts, and how far each puff strays from the wind.
 */
class HazeTest {
    /** particle/puff.fsh's lite_puff_integral: the integral of (h - a u^2)^2 from 0 to u. */
    private static double puffIntegral(double u, double h, double a) {
        double u2 = u * u;
        return u * (h * h - u2 * (2.0 / 3.0 * h * a - 0.2 * a * a * u2));
    }

    /**
     * The density (1 - s^2)^2 along a line through a sphere of radius r centred at the origin, as the
     * shader works it out: origin and direction in the sphere's own units, ends clipped to the eye
     * (t >= 0) and to the scene (t <= scene).
     */
    private static double shaderDensity(double[] eye, double[] ray, double radius, double scene) {
        double[] origin = {eye[0] / radius, eye[1] / radius, eye[2] / radius};
        double[] direction = {ray[0] / radius, ray[1] / radius, ray[2] / radius};
        double a = dot(direction, direction);
        double b = dot(origin, direction);
        double c = dot(origin, origin);
        double closest = -b / a;
        double coreness = 1.0 - c - b * closest;
        if (coreness <= 0.0) {
            return 0.0;
        }
        double halfChord = Math.sqrt(coreness / a);
        double enter = Math.max(closest - halfChord, 0.0);
        double leave = Math.min(closest + halfChord, scene);
        if (leave <= enter) {
            return 0.0;
        }
        return puffIntegral(leave - closest, coreness, a) - puffIntegral(enter - closest, coreness, a);
    }

    /** The same by small steps. */
    private static double steppedDensity(double[] eye, double[] ray, double radius, double scene) {
        int steps = 200_000;
        double end = Math.min(scene, 4.0 * radius + 10.0);
        double step = end / steps;
        double sum = 0.0;
        for (int i = 0; i < steps; i++) {
            double t = (i + 0.5) * step;
            double x = (eye[0] + ray[0] * t) / radius;
            double y = (eye[1] + ray[1] * t) / radius;
            double z = (eye[2] + ray[2] * t) / radius;
            double s2 = x * x + y * y + z * z;
            if (s2 < 1.0) {
                sum += (1.0 - s2) * (1.0 - s2) * step;
            }
        }
        return sum;
    }

    private static double dot(double[] p, double[] q) {
        return p[0] * q[0] + p[1] * q[1] + p[2] * q[2];
    }

    private static double[] unit(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / length, y / length, z / length};
    }

    @Test
    void thePuffIntegralIsExactThroughTheMiddleOffCentreClippedAndFromInside() {
        double radius = 4.0;
        // Straight through the middle: (16/15) r.
        assertEquals(16.0 / 15.0 * radius, shaderDensity(new double[] {0, 0, -10}, unit(0, 0, 1), radius, 1.0e9), 1.0e-9);
        double[][][] cases = {
            {{0.0, 1.5, -10.0}, {0.05, 0.0, 1.0}},
            {{2.0, -1.0, -9.0}, {-0.2, 0.1, 1.0}},
            {{0.5, 0.2, 0.3}, {0.3, 0.9, -0.2}},
            {{1.0, 3.0, -2.0}, {0.0, -0.8, 0.6}},
        };
        for (double[][] line : cases) {
            double[] ray = unit(line[1][0], line[1][1], line[1][2]);
            for (double scene : new double[] {1.0e9, 9.5, 3.0, 0.4}) {
                assertEquals(steppedDensity(line[0], ray, radius, scene), shaderDensity(line[0], ray, radius, scene), 1.0e-4,
                    "line from " + line[0][0] + "," + line[0][1] + "," + line[0][2] + " to a scene at " + scene);
            }
        }
    }

    @Test
    void thePuffThinsToNothingAtItsOutlineAndWhereItMeetsTheScene() {
        double radius = 4.0;
        // Lines passing closer and closer to the outline carry less and less, down to none: no rim.
        double previous = Double.MAX_VALUE;
        for (double miss = 3.0; miss <= 4.0; miss += 0.05) {
            double density = shaderDensity(new double[] {0, miss, -10}, unit(0, 0, 1), radius, 1.0e9);
            assertTrue(density <= previous);
            previous = density;
        }
        assertEquals(0.0, shaderDensity(new double[] {0, 3.999, -10}, unit(0, 0, 1), radius, 1.0e9), 1.0e-6);
        // A wall just behind where the line enters takes almost everything: the puff fades into it.
        double entering = 10.0 - radius;
        assertEquals(0.0, shaderDensity(new double[] {0, 0, -10}, unit(0, 0, 1), radius, entering + 0.01), 1.0e-6);
    }

    @Test
    void aPuffSurvivesThePackingIntoTheVertexFormat() {
        for (ParticleKind.Haze haze : new ParticleKind.Haze[] {ParticleKind.Haze.MIST, ParticleKind.Haze.SAND, ParticleKind.Haze.ASH,
                ParticleKind.Haze.END}) {
            for (int seed : new int[] {0, -1, 0x12345678, 0x7FFFFFF0, Integer.MIN_VALUE}) {
                float code = ParticleBatch.puffCode(haze, seed);
                assertEquals(code, (float) Math.rint(code), "a whole number, which a float holds exactly");
                // As particle/puff.vsh reads it back, from either corner's sign.
                for (float signed : new float[] {code, -code}) {
                    double decoded = Math.floor(Math.abs(signed) - 1.0 + 0.5);
                    assertEquals(haze.ordinal() - 1, (int) (decoded % 4.0));
                    assertEquals((seed >>> 4) & 255, (int) Math.floor(decoded / 4.0));
                }
            }
        }
        assertEquals(6.5f, Math.abs(-ParticleBatch.puffSize(6.5f)) - 1.0f, 1.0e-6f);
        assertTrue(ParticleBatch.puffSize(0.0f) > 0.0f, "a corner's sign survives a size of 0");
    }

    @Test
    void theHazeKindsAreTaggedAndNothingElseIs() {
        assertEquals(ParticleKind.Haze.MIST, ParticleKind.MIST.haze);
        assertEquals(ParticleKind.Haze.SAND, ParticleKind.SAND_CLOUD.haze);
        assertEquals(ParticleKind.Haze.ASH, ParticleKind.ASH_HAZE.haze);
        assertEquals(ParticleKind.Haze.END, ParticleKind.END_MIST.haze);
        int tagged = 0;
        for (ParticleKind kind : ParticleKind.all()) {
            if (kind.haze != ParticleKind.Haze.NONE) {
                tagged++;
                assertEquals(ParticleKind.Layer.SOFT, kind.layer, kind.name);
            }
        }
        assertEquals(4, tagged);
    }

    @Test
    void puffsGatherInClumpsAndTheRateMakesUpForThoseTurnedAway() {
        double sum = 0.0;
        int count = 0;
        float least = 1.0f;
        float most = 0.0f;
        for (int x = 0; x < 2048; x += 4) {
            for (int z = 0; z < 2048; z += 4) {
                float share = HazeClumps.share(x, z, 0.0, 0.0);
                sum += share;
                count++;
                least = Math.min(least, share);
                most = Math.max(most, share);
            }
        }
        assertEquals(1.0, sum / count * HazeClumps.RATE_SCALE, 0.03, "about as much haze as without clumps");
        assertTrue(least < 0.2f && most > 0.95f, "thin spots and thick ones");
        assertTrue(least >= HazeClumps.FLOOR - 1.0e-6f);
        // It repeats every 2048 blocks, as the drift wraps, so a wrap never moves it.
        assertEquals(HazeClumps.share(100.3, -57.9, 12.0, 3.0), HazeClumps.share(100.3 + 2048.0, -57.9, 12.0, 3.0), 1.0e-5f);
        assertEquals(HazeClumps.share(100.3, -57.9, 12.0, 3.0), HazeClumps.share(100.3, -57.9, 12.0 - 2048.0, 3.0 + 2048.0), 1.0e-5f);
    }

    @Test
    void theDriftFollowsTheWindWrapsWithoutAJumpAndStopsWithTheTicks() {
        HazeDrift drift = new HazeDrift();
        Vector4f out = new Vector4f(0.0f, 0.0f, 0.0f, 7.0f);
        drift.tick(0.5f, -0.25f);
        drift.write(out, 1.0f);
        assertEquals(0.5f, out.x, 1.0e-6f);
        assertEquals(-0.25f, out.z, 1.0e-6f);
        assertEquals(7.0f, out.w, "w is someone else's");
        // Through a wrap, a frame halfway into the next tick is half a tick's travel on, as the
        // shaders see it (positions repeat every 2048 blocks), and the two ticks never straddle it.
        for (int i = 0; i < 5000; i++) {
            drift.write(out, 1.0f);
            float before = out.x;
            drift.tick(0.5f, -0.25f);
            drift.write(out, 0.5f);
            double moved = Math.floorMod(Math.round((out.x - before) * 1000.0), Math.round(HazeDrift.WRAP * 1000.0)) / 1000.0;
            assertEquals(0.25, moved, 1.0e-3);
            drift.write(out, 0.0f);
            float start = out.x;
            drift.write(out, 1.0f);
            assertEquals(0.5f, out.x - start, 1.0e-3f, "the two ticks are wrapped together");
        }
        assertTrue(Math.abs(drift.x()) < HazeDrift.WRAP);
        // No ticks (the game paused): the frame value doesn't change, whatever the partial tick.
        drift.write(out, 0.3f);
        float paused = out.x;
        drift.write(out, 0.3f);
        assertEquals(paused, out.x);
    }

    @Test
    void eachPuffTakesTheWindALittleDifferently() {
        float slowest = Float.MAX_VALUE;
        float fastest = 0.0f;
        float widest = 0.0f;
        for (int seed = 0; seed < 1 << 20; seed += 4093) {
            int mixed = seed * 0x9E3779B1;
            slowest = Math.min(slowest, Simulation.hazeWindSpeed(mixed));
            fastest = Math.max(fastest, Simulation.hazeWindSpeed(mixed));
            widest = Math.max(widest, Math.abs(Simulation.hazeWindVeer(mixed)));
        }
        assertTrue(slowest >= 0.65f && slowest < 0.7f, "slowest " + slowest);
        assertTrue(fastest <= 1.35f && fastest > 1.3f, "fastest " + fastest);
        assertTrue(widest <= 0.35f && widest > 0.3f, "widest veer " + widest);
    }

    @Test
    void theFieldFillsTheAirAsThePuffsWould() {
        // Twice the puffs, or twice as opaque: twice the extinction; twice the volume: half.
        float base = HazeField.extinction(1.6f, 220, 0.13f, 7.5f, HazeField.ring(7.0f, 31.0f), 6.0f);
        assertTrue(base > 0.005f && base < 0.2f, "rain mist extinction " + base);
        assertEquals(2.0f * base, HazeField.extinction(3.2f, 220, 0.13f, 7.5f, HazeField.ring(7.0f, 31.0f), 6.0f), 1.0e-6f);
        assertEquals(2.0f * base, HazeField.extinction(1.6f, 220, 0.26f, 7.5f, HazeField.ring(7.0f, 31.0f), 6.0f), 1.0e-6f);
        assertEquals(0.5f * base, HazeField.extinction(1.6f, 220, 0.13f, 7.5f, HazeField.ring(7.0f, 31.0f), 12.0f), 1.0e-6f);
        assertEquals(0.0f, HazeField.extinction(1.6f, 220, 0.13f, 7.5f, 0.0f, 6.0f));
        assertEquals((float) (Math.PI * (31.0 * 31.0 - 7.0 * 7.0)), HazeField.ring(7.0f, 31.0f), 1.0e-2f);
    }

    @Test
    void thickHazeStopsAddingUpInTheField() {
        // Light haze passes nearly unchanged; a blizzard's pile of puffs much less than its sum.
        assertEquals(0.01f, HazeField.saturated(0.01f), 0.001f);
        float previous = 0.0f;
        for (float extinction = 0.01f; extinction < 3.0f; extinction += 0.01f) {
            float saturated = HazeField.saturated(extinction);
            assertTrue(saturated > previous && saturated <= extinction, "rises, never above what was offered");
            previous = saturated;
        }
        assertTrue(HazeField.saturated(1.65f) < 0.4f, "a blizzard no longer closes the view in within a block");
    }
}
